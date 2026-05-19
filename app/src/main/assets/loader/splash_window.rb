module Loader
  # Wraps LiteRGSS::DisplayWindow + Viewport. Owns the canvas size
  # computation, the focus callbacks (Android pause/resume), and the
  # touch-coord scale-down from surface pixels to logical canvas units.
  #
  # Touch callbacks delivered to on_touch_* are in logical canvas
  # coordinates — the scaling happens here so downstream consumers
  # (TouchRouter) don't have to know the surface size.
  class SplashWindow
    # Logical canvas short-edge size in PSDK pixels (matches PSDK's
    # native 240 height). The long edge is derived at runtime from the
    # underlying surface's aspect ratio so the splash fills the screen
    # in either orientation without letterboxing.
    LOGICAL_SHORT_EDGE = 240
    FALLBACK_LANDSCAPE = [320, 240].freeze
    WINDOW_SCALE       = 2

    attr_reader :viewport, :width, :height
    attr_accessor :on_touch_began, :on_touch_moved, :on_touch_ended

    def initialize
      @width, @height = compute_logical_size
      @closed = false
      @has_focus = true
      # Touch events arrive in surface-pixel coordinates; the icon is
      # laid out in logical canvas units. Compute the per-axis scale
      # once and cache it, since the surface size doesn't change while
      # the loader runs (orientation change tears the activity down).
      dw = (LiteRGSS::DisplayWindow.desktop_width  rescue 0).to_i
      dh = (LiteRGSS::DisplayWindow.desktop_height rescue 0).to_i
      @touch_scale_x = dw > 0 ? @width.to_f  / dw : 1.0
      @touch_scale_y = dh > 0 ? @height.to_f / dh : 1.0
    end

    def open(title)
      # Args: title, width, height, scale, bpp, framerate, vsync,
      # fullscreen, mouse_visible
      @window = LiteRGSS::DisplayWindow.new(
        title, @width, @height, WINDOW_SCALE, 32, 60, true, false, false
      )
      # litergss2 has no should_close? — the close signal comes via
      # this callback. Returning truthy lets the C side raise
      # rb_eClosedWindow, which is the right behavior for a real Closed
      # event (user-driven window close). Android activity teardown no
      # longer flows through here — surface destroy/recreate uses
      # SFML's LostFocus/GainedFocus path, and terminal shutdown uses
      # $rgss_should_stop from CompileWizardFragment.onDestroy.
      @window.on_closed = proc { @closed = true; next true }
      # PSDK's Android pause-resume strategy: flip polling so the next
      # event pump blocks in waitEvent instead of busy-looping with
      # pollEvent. The @has_focus flag is what `tick` reads to skip
      # drawing entirely while the surface is gone.
      @window.on_lost_focus = proc do
        @has_focus = false
        @window.polling = false if @window
      end
      @window.on_gained_focus = proc do
        @has_focus = true
        @window.polling = true if @window
      end
      @window.on_touch_began = proc do |finger, tx, ty|
        @on_touch_began&.call(finger, tx * @touch_scale_x, ty * @touch_scale_y)
      end
      @window.on_touch_moved = proc do |_finger, tx, ty|
        @on_touch_moved&.call(tx * @touch_scale_x, ty * @touch_scale_y)
      end
      @window.on_touch_ended = proc do |finger, tx, ty|
        @on_touch_ended&.call(finger, tx * @touch_scale_x, ty * @touch_scale_y)
      end

      # litergss2's Viewport ctor REQUIRES a DisplayWindow as first arg
      # (Viewport.cpp:47). litergss3 made it optional via a process-
      # global window lookup, but on Android we're on the older API.
      @viewport = LiteRGSS::Viewport.new(@window, 0, 0, @window.width, @window.height)
      @viewport.z = 1_000_000
    end

    def closed?
      @closed || @window.nil?
    end

    def has_focus?
      @has_focus
    end

    # When the activity is paused (phone locked / app backgrounded), the
    # GL surface gets destroyed under us. `window.update` draws BEFORE
    # processing events (see DisplayWindowInput.cpp:213), so once focus
    # is lost we route through `update_only_input` instead — that path
    # skips the draw and blocks in waitEvent (because on_lost_focus
    # flipped polling=false) until focus comes back. Prevents the
    # SIGSEGV from SFML touching a dead EGLSurface.
    def update
      @window.update
    end

    def update_only_input
      @window.update_only_input
    end

    def dispose
      @viewport.dispose if @viewport && !@viewport.disposed?
      @viewport = nil
      @window.dispose if @window
      @window = nil
    end

    private

    # Pin the short edge to LOGICAL_SHORT_EDGE and scale the long edge
    # by the surface aspect ratio. Querying DisplayWindow.desktop_*
    # before opening the window is supported (singleton method on the
    # C++ side) and is the only orientation signal we have at this
    # point. Falls back to PSDK's classic 320x240 landscape if SFML
    # reports nonsense (e.g. before the surface is attached).
    def compute_logical_size
      dw = (LiteRGSS::DisplayWindow.desktop_width  rescue 0).to_i
      dh = (LiteRGSS::DisplayWindow.desktop_height rescue 0).to_i
      return FALLBACK_LANDSCAPE if dw <= 0 || dh <= 0
      if dh > dw
        [LOGICAL_SHORT_EDGE, LOGICAL_SHORT_EDGE * dh / dw]
      else
        [LOGICAL_SHORT_EDGE * dw / dh, LOGICAL_SHORT_EDGE]
      end
    end
  end
end
