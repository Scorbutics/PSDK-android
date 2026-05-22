unless defined?(Loader::Splash::INTRO_FRAMES)

module Loader
  # Composes the clock, scene state, window, asset bundle, layers,
  # touch router, and status-file poller. The orchestrator at the
  # bottom of loader.rb instantiates one of these, calls show! +
  # run_intro, then drives tick in a loop.
  class Splash
    INTRO_FRAMES       = 120
    STATUS_HOLD        = 20    # frames each set_status holds before returning
    # How long "Initializing…" stays on screen before flipping to the
    # CTA. Used by the bottom-of-file script when the activity didn't
    # already supply a real sub-step label.
    INIT_TO_READY_SECS = 3.0

    # Logged exactly once, on the LogListener, the first time
    # @window.update actually flips a frame onto the EGL surface. The
    # host activity matches against this verbatim to fade its
    # SurfaceView in only after pixels are really on screen — change
    # here, change RootActivity.kt together.
    READY_SENTINEL = '[loader-ready]'.freeze

    # Logged every time a touch lands inside the icon's hit rect. The
    # compile wizard activity matches against this verbatim to advance
    # from the import step to the compile step — change here, change
    # CompileWizardFragment.kt together. The fragment itself decides
    # whether the tap is actionable (archive validated, still on step
    # 0), so we emit on every hit rather than one-shot.
    ICON_TAPPED_SENTINEL = '[loader-icon-tapped]'.freeze

    def initialize
      @clock  = Clock.new
      @state  = SceneState.new
      @window = SplashWindow.new
      @layers = []
    end

    def show!
      font_loaded   = Assets.ensure_font_loaded
      @project_info = Assets.load_project_info
      icon_color    = Assets.load_icon_color
      icon_bitmap   = Assets.load_icon_bitmap

      @window.open(@project_info[:title])

      @bg      = Layers::Background.new(icon_color)
      @ambient = Layers::AmbientParticles.new
      @dust    = Layers::Dust.new
      @icon    = Layers::Icon.new(icon_color, icon_bitmap)
      @text    = Layers::Text.new(@project_info, font_loaded: font_loaded)

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

      @status_file = StatusFile.new(
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
      if @window.has_focus?
        @clock.tick!
        m = @clock.fade_out_multiplier
        @layers.each { |l| l.animate(@clock, @state, m) }
        @status_file.poll
        @window.update
        unless @state.first_frame_signaled
          @state.first_frame_signaled = true
          loader_say READY_SENTINEL
        end
      else
        @window.update_only_input
      end
      true
    end

    def run_intro
      INTRO_FRAMES.times { return unless tick }
    end

    # Tick for `seconds` real-time seconds (rounded to the nearest
    # frame at Clock::FRAME_RATE). Returns false early if the
    # underlying window closes — callers should bail in that case,
    # same contract as #tick.
    def hold_seconds(seconds)
      (seconds * Clock::FRAME_RATE).round.times { return false unless tick }
      true
    end

    # Swap the status text and hold long enough for the user to read it.
    def set_status(text)
      return unless @text.status_present?
      @text.update_status(text)
      STATUS_HOLD.times { return unless tick }
    end

    # True iff StatusFile has overwritten the status text at least once
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
      Clock::OUTRO_FRAMES.times { break unless tick }
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
