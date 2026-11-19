unless defined?(Loader::Splash::INTRO_FRAMES)

module Loader
  # Composes the clock, scene state, window, asset bundle, layers,
  # touch router, and host message reader. The orchestrator at the
  # bottom of loader.rb instantiates one of these, calls show! +
  # run_intro, then drives tick in a loop.
  class Splash
    INTRO_FRAMES       = 120
    STATUS_HOLD        = 20    # frames each set_status holds before returning
    # How long "Initializing…" stays on screen before flipping to the
    # CTA. Used by the bottom-of-file script when the activity didn't
    # already supply a real sub-step label.
    INIT_TO_READY_SECS = 3.0

    # Host event sent exactly once, the first time @window.update
    # actually flips a frame onto the EGL surface: the host activity
    # fades its SurfaceView in only after pixels are really on screen.
    # Matches RootActivity.LOADER_READY_EVENT.
    READY_EVENT = 'loader.ready'.freeze

    # Host event sent every time a touch lands inside the icon's hit rect.
    # The compile wizard advances from the import step to the compile step
    # on it; the fragment decides whether the tap is actionable (archive
    # validated, still on step 0), so it goes on every hit rather than
    # once. Matches RootActivity.LOADER_ICON_TAPPED_EVENT.
    ICON_TAPPED_EVENT = 'loader.icon_tapped'.freeze

    def initialize
      @clock  = Clock.new
      @state  = SceneState.new
      @window = SplashWindow.new
      @layers = []
    end

    def show!
      # Closed before we even had a window: leave it unopened, so every
      # tick/hold reports closed and loader.rb unwinds to its ensure.
      if loader_stop_requested?
        loader_say 'stop requested before the window was created'
        return
      end
      @project_info = Assets.load_project_info
      icon_color    = Assets.load_icon_color
      preparing     = Assets.load_preparing_message

      @window.open(@project_info[:title])
      # After the window: LiteRGSS3 (raylib) builds a font's atlas, and a
      # bitmap's texture, in its GL context, which the window makes. Loaded
      # before it, the font failed quietly and the splash had no text on
      # its first run (the window is kept for the next ones): a small APK's
      # "Preparing the game" and download progress.
      font_loaded   = Assets.ensure_font_loaded
      icon_bitmap   = Assets.load_icon_bitmap

      @bg      = Layers::Background.new(icon_color)
      @ambient = Layers::AmbientParticles.new
      @dust    = Layers::Dust.new
      # While preparing there is no project to show and no "+" button over
      # the centre: no halo, and the message replaces the hub's hint.
      @icon    = Layers::Icon.new(icon_color, icon_bitmap, halo: preparing.nil?)
      @text    = Layers::Text.new(@project_info, font_loaded: font_loaded, preparing_message: preparing,
                                                  font_ids: Assets.method(:font_id))

      @bg.build(@window.viewport)
      @ambient.build(@window.viewport)
      @dust.build(@window.viewport)
      @icon.build(@window.viewport, @window.width, @window.height)
      @text.build(@window.viewport, @window.width, @window.height, @icon)

      @layers = [@bg, @ambient, @dust, @icon, @text]

      @touch_router = TouchRouter.new(state: @state, icon: @icon, dust: @dust, clock: @clock)
      @window.on_touch_began = ->(finger, lx, ly) { @touch_router.handle_began(finger, lx, ly) }
      @window.on_touch_moved = ->(lx, ly)          { @touch_router.handle_moved(lx, ly) }
      @window.on_touch_ended = ->(finger, lx, ly) { @touch_router.handle_ended(finger, lx, ly) }

      @host_messages = HostMessages.new(
        state: @state, text_layer: @text, dust: @dust, icon: @icon, clock: @clock
      )

      # Anchor wall-clock AFTER all build() allocations — first-frame
      # work is heavy (gradient gen, glow falloff). Starting the clock
      # here means the intro reveals at the expected real-world pace
      # regardless of cold-start cost.
      @clock.start!
    end

    # Pump one animation frame. Returns false if the window has been
    # closed (e.g. user dismissed it) — callers should bail.
    def tick
      return false if @window.closed?
      return false if (@clock.frame % LOADER_STOP_POLL_INTERVAL_F).zero? && loader_stop_requested?
      @clock.tick!
      m = @clock.fade_out_multiplier
      @layers.each { |l| l.animate(@clock, @state, m) }
      @host_messages.poll
      # In the background, the runtime pauses this update until the
      # surface is back (or a close request comes)
      @window.update
      @clock.pace!
      unless @state.first_frame_signaled
        @state.first_frame_signaled = true
        loader_emit READY_EVENT
      end
      true
    end

    def run_intro
      tick_for(INTRO_FRAMES)
    end

    # Tick for `seconds` real-time seconds (rounded to the nearest
    # frame at Clock::FRAME_RATE). Returns false early if the
    # underlying window closes — callers should bail in that case,
    # same contract as #tick.
    def hold_seconds(seconds)
      tick_for((seconds * Clock::FRAME_RATE).round)
    end

    # Ticks until `frames` clock frames (Clock::FRAME_RATE, wall time) have
    # passed, however many are drawn meanwhile. Same contract as #tick.
    def tick_for(frames)
      return false unless tick

      until_frame = @clock.frame + frames
      while @clock.frame < until_frame
        return false unless tick
      end
      true
    end

    # Swap the status text and hold long enough for the user to read it.
    def set_status(text)
      return unless @text.status_present?
      @text.update_status(text)
      tick_for(STATUS_HOLD)
    end

    # True iff HostMessages has overwritten the status text at least once
    # with a real activity-supplied payload (a compile sub-step,
    # typically). The bottom-of-file script reads this after the intro
    # hold to decide whether to flash the manual-tap CTA; in
    # classic-bootstrap mode the auto-tap + first sub-step land before
    # the hold ends, so the CTA would just clobber "Check engine…".
    def external_status_received?
      @state.external_status_received
    end

    def fade_out_and_dispose
      @clock.request_fade_out
      tick_for(Clock::OUTRO_FRAMES)
      dispose
    end

    def dispose
      @layers.each(&:dispose)
      @layers = []
      @window.dispose
    end
  end
end

end # unless defined?(Loader::Splash::INTRO_FRAMES)
