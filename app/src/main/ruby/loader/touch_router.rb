unless defined?(Loader::TouchRouter)

module Loader
  # Maps the SplashWindow's touch callbacks onto SceneState mutations
  # and Dust spawns. Receives logical canvas coordinates already (the
  # window does the surface→canvas scaling). The activity is still the
  # arbiter of icon-tap actionability — the loader only fires
  # ICON_TAPPED_SENTINEL on every hit; the activity decides whether to
  # advance.
  class TouchRouter
    def initialize(state:, icon:, dust:, clock:)
      @state = state
      @icon  = icon
      @dust  = dust
      @clock = clock
    end

    def handle_began(finger, lx, ly)
      @dust.spawn(lx, ly, Layers::Dust::TAP_COUNT, born_at: @clock.frame)
      return unless @icon.hit?(lx, ly)
      return if @state.pressed_finger   # already held by another finger
      @state.pressed_finger = finger
    end

    def handle_moved(lx, ly)
      @dust.spawn_throttled(lx, ly, Layers::Dust::MOVE_COUNT, @clock.frame)
    end

    def handle_ended(finger, lx, ly)
      if @state.pressed_finger == finger
        @state.pressed_finger = nil
        @dust.bang(@icon.center_x, @icon.center_y, born_at: @clock.frame)
        @state.shake_start = @clock.frame
        # First tap kicks off the rolling animation; later taps keep
        # the same spin start so the speed ramp doesn't visibly reset.
        @state.rolling_start ||= @clock.frame
        loader_say Splash::ICON_TAPPED_SENTINEL
      else
        @dust.spawn(lx, ly, Layers::Dust::RELEASE_COUNT, born_at: @clock.frame)
      end
    end
  end
end

end # unless defined?(Loader::TouchRouter)
