unless defined?(Loader::SplashWindow::LOGICAL_SHORT_EDGE)

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
      @first_pump = true
      @skip_resize = false
      # Touch events arrive in surface-pixel coordinates; the icon is
      # laid out in logical canvas units. The surface can change size
      # while the loader runs (RootActivity keeps itself alive across
      # rotation, fold and multi-window resizes), so the scale is
      # refreshed from every Resized event too (see #open).
      update_touch_scale(
        (LiteRGSS::DisplayWindow.desktop_width  rescue 0).to_i,
        (LiteRGSS::DisplayWindow.desktop_height rescue 0).to_i
      )
    end

    def open(title)
      # Args: title, width, height, scale, bpp, framerate, vsync,
      # fullscreen, mouse_visible
      @window = LiteRGSS::DisplayWindow.new(
        title, @width, @height, WINDOW_SCALE, 32, 60, true, false, false
      )
      # litergss2 has no should_close? — the close signal comes via
      # this callback: RubyWorkloadController's requestClose. Returning
      # truthy lets the C side raise rb_eClosedWindow, which unwinds the
      # splash. Surface destroy/recreate doesn't flow through here, it
      # uses SFML's LostFocus/GainedFocus path.
      #
      # On the first event pump, a Closed the stop marker doesn't back is
      # ignored: the window is shared between splash runs, and a run that
      # left before pumping events leaves its close request queued for the
      # next one (which only starts once that run has ended, so later
      # Closed events are all its own). On a false return litergss2 falls
      # through to its Resized case (DisplayWindowInput.cpp, no break) with
      # the size fields a Closed never sets: that call is skipped.
      @window.on_closed = proc do
        if @first_pump && !loader_stop_requested?
          loader_say 'stale close request ignored'
          @skip_resize = true
          next false
        end

        @closed = true
        next true
      end
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
      # Keeps taps on the icon after the surface is resized; the canvas
      # itself is rebuilt by RootActivity restarting the loader.
      @window.on_resized = proc do |surface_w, surface_h|
        if @skip_resize
          @skip_resize = false
        else
          update_touch_scale(surface_w.to_i, surface_h.to_i)
        end
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
      pumped
    end

    def update_only_input
      @window.update_only_input
      pumped
    end

    def dispose
      @viewport.dispose if @viewport && !@viewport.disposed?
      @viewport = nil
      @window.dispose if @window
      @window = nil
    end

    private

    # After each event pump (see #open's on_closed)
    def pumped
      @first_pump = false
      @skip_resize = false
    end

    # Surface pixels -> logical canvas units, per axis.
    def update_touch_scale(surface_w, surface_h)
      @touch_scale_x = surface_w > 0 ? @width.to_f  / surface_w : 1.0
      @touch_scale_y = surface_h > 0 ? @height.to_f / surface_h : 1.0
    end

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

end # unless defined?(Loader::SplashWindow::LOGICAL_SHORT_EDGE)
