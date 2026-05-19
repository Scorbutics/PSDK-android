# Standalone splash loop.
#
# Runs as the main script enqueued by CompileWizardActivity and
# ClassicSetupActivity in its own PsdkInterpreter, separate from the
# compile interpreter. Drawn entirely with LiteRGSS primitives
# (DisplayWindow, Viewport, Sprite, Image, Color, Text), with no reliance
# on PSDK's reopened Sprite / Graphics / Text.
#
# Reads project metadata / icon / font from <pwd>/loader_assets/ — files
# extract_loader_assets.rb wrote during archive validation. That means
# this script has zero archive dependency: no PhysFS, no ArchiveMount, no
# EpsaStream. The mount is owned by check_engine.rb on the compile side.
#
# Lifecycle:
#   1. Bootstrap (LiteRGSS require).
#   2. Loader.new + show! + run_intro.
#   3. Tick the animation forever, bailing when the window is closed
#      (e.g. activity destroyed) — the activity tears the loader
#      interpreter down by closing it.

def loader_say(msg)
  puts "[loader] #{msg}"
rescue StandardError
  STDERR.puts "[loader] #{msg}" rescue nil
end

require 'LiteRGSS'
LiteRGSS::Shader.available = false

class Loader
  INTRO_FRAMES   = 120
  OUTRO_FRAMES   = 60
  STATUS_HOLD    = 20  # frames each set_status holds before returning
  # Bumped from 96 → 144 when the splash became the activity's main
  # content (compile wizard archive card is hidden once the archive is
  # valid). Bigger halo reads as "this is the centerpiece" — also gives
  # the tap-target visual weight commensurate with its new role.
  GLOW_SIZE      = 144
  PARTICLE_COUNT = 9

  # Matches the framerate passed to DisplayWindow.new in open_window.
  # Used by anything that wants to express timing in seconds rather than
  # frames (hold-then-flip-status).
  FRAME_RATE             = 60
  # How long "Initializing…" stays on screen before flipping to the CTA.
  INIT_TO_READY_SECS     = 3.0

  # Per-text font sizes registered under font id 0. LiteRGSS::Text honors
  # an explicit `size=` per instance — these are the sizes we apply to the
  # title / version / CTA / footer to establish visual hierarchy. Values
  # are in font points, the same unit as Fonts.set_default_size.
  TITLE_FONT_SIZE   = 20
  VERSION_FONT_SIZE = 11
  CTA_FONT_SIZE     = 12
  FOOTER_FONT_SIZE  = 9

  # Logical canvas short-edge size in PSDK pixels (matches PSDK's native
  # 240 height). The long edge is derived at runtime from the underlying
  # surface's aspect ratio so the splash fills the screen in either
  # orientation without letterboxing — see [compute_logical_size].
  LOGICAL_SHORT_EDGE = 240
  FALLBACK_LANDSCAPE = [320, 240].freeze
  WINDOW_SCALE       = 2

  # Asset paths under <pwd>/loader_assets/ — extract_loader_assets.rb
  # writes these during archive validation. Filenames are stable / generic
  # because the source font may have been any *.ttf inside the archive's
  # Fonts/ dir (PokemonDS.ttf preferred, else fallback).
  DEFAULT_FONT_PATH = 'loader_assets/game_font.ttf'
  ICON_PATH         = 'loader_assets/game.png'
  INFOS_CONFIG_PATH = 'loader_assets/infos_config.json'
  ICON_COLOR_PATH   = 'loader_assets/icon_color.json'

  # Fallback gradient hue when extract_loader_assets.rb didn't write
  # icon_color.json (e.g. all-transparent icon, decode failure).
  # Deep indigo — matches the pre-detection splash look.
  DEFAULT_ICON_COLOR = [60, 90, 200].freeze

  # Logged exactly once, on the LogListener, the first time `@window.update`
  # actually flips a frame onto the EGL surface. The host activity matches
  # against this verbatim to fade its SurfaceView in only after pixels are
  # really on screen — change here, change CompileWizardActivity.kt and
  # ClassicSetupActivity.kt together.
  READY_SENTINEL = '[loader-ready]'.freeze

  # Logged every time a touch lands inside the icon's hit rect. The compile
  # wizard activity matches against this verbatim to advance from the
  # import step to the compile step — change here, change
  # CompileWizardActivity.kt together. The activity itself decides whether
  # the tap is actionable (archive validated, still on step 0), so we
  # emit on every hit rather than one-shot.
  ICON_TAPPED_SENTINEL = '[loader-icon-tapped]'.freeze

  # One-line status file the compile wizard writes to drive the splash's
  # status_text during compilation — see CompileProgressFragment.kt
  # (LOADER_STATUS_FILE constant matches this name verbatim). Relative
  # path is fine because PsdkInterpreter.enqueue does Dir.chdir to the
  # executionLocation before running this script.
  STATUS_FILE_PATH        = '.loader_status'.freeze
  STATUS_POLL_INTERVAL_F  = 8  # ~7.5 Hz at 60Hz — much faster than humans read
  STATUS_FILE_BYTE_LIMIT  = 256 # guard vs. accidental dump of a megabyte log

  # Extra logical-px halo around the icon bitmap, so the tap target is
  # forgiving — the icon is ~64px but a fingertip is much wider.
  ICON_HIT_PADDING = 24

  # Stardust feedback layer — every touch spawns short-lived sparkles at
  # the contact point; release-on-icon triggers a radial "bang" plus a
  # decaying shake before the compile sentinel goes out.
  DUST_BITMAP_SIZE   = 4
  DUST_LIFE_FRAMES   = 36     # 0.6s at 60Hz — long enough to read, short enough to feel snappy
  MAX_DUST           = 96     # hard ceiling — protects vs. finger-mashing while bitmap is shared
  DUST_TAP_COUNT     = 8      # particles per touch-began
  DUST_MOVE_COUNT    = 1      # particles per touch-moved frame (rate-limited to once per @frame)
  DUST_RELEASE_COUNT = 4      # particles per non-icon touch-ended
  BANG_COUNT         = 24     # radial burst on icon release
  BANG_SPEED_MIN     = 2.5
  BANG_SPEED_MAX     = 5.0

  # Press feedback: icon zoom is offset by PRESS_DEPRESSION when fully
  # pressed, eased toward target with PRESS_SPRING per frame (critically
  # damped feel — no overshoot). Breathing is suppressed proportionally
  # so a pressed icon really does feel "frozen and smaller".
  PRESS_DEPRESSION = 0.12
  PRESS_SPRING     = 0.35

  # Release shake — a decaying high-frequency sine on both axes. SHAKE_FRAMES
  # caps the duration; SHAKE_AMPLITUDE is the peak displacement on frame 0.
  SHAKE_FRAMES    = 22
  SHAKE_AMPLITUDE = 7

  # Post-tap "rolling" rotation. Triggered the moment the icon is released
  # (alongside shake + bang + sentinel) and runs until the loader is
  # disposed, signalling "I'm working" while the compile engine grinds
  # away. ROLL_SPEED_DEG_PER_FRAME is the steady-state rate; the icon
  # eases into it over ROLL_RAMP_FRAMES so the spin feels propelled
  # by the tap rather than abruptly switched on. ROLL_DIRECTION is the
  # sign of the angular step — +1 = clockwise, -1 = counter-clockwise.
  # Counter-clockwise pairs better with the upward bias of the dust
  # bang (sparkles fly up + out, icon spins back). 5 deg/frame * 60Hz
  # = 300 deg/sec, ~one full turn every 1.2s — visibly fast, matches
  # the warp-speed feel of PARTICLE_SPEED_BOOST without becoming a blur.
  ROLL_SPEED_DEG_PER_FRAME = 25.0
  ROLL_DIRECTION           = -1
  ROLL_RAMP_FRAMES         = 30

  def initialize(font_path: DEFAULT_FONT_PATH)
    # Wall-clock-driven animation. @frame is derived in [tick] from the
    # monotonic clock anchored to @wall_start_at (set in [show!]), and
    # @dframe is the delta-frames since the previous tick. Integrators
    # (positions, angles, velocity decay) scale by @dframe so the
    # animation "catches up" to wall time after a GVL hold — the compile
    # interpreter shares the Ruby VM with this script, so heavy C-ext
    # phases (graphics encode, archive deflate) can stall ticks for
    # hundreds of ms. Phase-based animations (sin/cos, eased reveals)
    # automatically track wall time since they're keyed off @frame
    # absolute — no per-call multiplication needed.
    @frame = 0
    @dframe = 0
    @wall_start_at = nil
    @fade_out_start = nil
    @fade_out_frames = OUTRO_FRAMES
    @font_loaded = false
    @font_path = font_path
    @window_closed = false   # litergss2 has no should_close?; we wire it via on_closed=
    @has_focus = true        # Android lock-screen sets this false via on_lost_focus
    @first_frame_signaled = false
    @window_width, @window_height = compute_logical_size
    # Icon-as-centerpiece layout. The splash used to be anchored to a
    # 240-px-tall band at the bottom of the canvas (relic of when the
    # SurfaceView was a small bottom strip); now the GL surface IS the
    # screen, so everything orbits the icon at canvas center. Text
    # positions (title above, caption below) are derived from these in
    # [build_text_layer] using the icon's actual bitmap dimensions.
    @icon_cx = @window_width / 2
    @icon_cy = @window_height / 2
    # Placeholder until [build_icon] learns the real bitmap size — keeps
    # [build_text_layer] safe if the icon fails to load.
    @icon_w, @icon_h = 64, 64
    # Logical-canvas rect of the icon, populated by [build_icon] once the
    # bitmap dimensions are known. nil means "tap target not ready yet".
    @icon_hit_rect = nil

    # Press / shake / dust state. @pressed_finger holds the SFML finger id
    # of the touch that landed inside the icon hit rect — non-nil means
    # "icon is currently held down". @press_factor smoothly tracks toward
    # that intent (1.0 pressed, 0.0 released) so the depression eases
    # rather than snaps. @shake_start is a frame index; the shake plays
    # for SHAKE_FRAMES after that and then resets to nil.
    @pressed_finger  = nil
    @press_factor    = 0.0
    @shake_start     = nil
    # Rolling spin. @rolling_start is the frame index when the user tapped
    # the icon (== when compile kicks off); @icon_angle is the
    # authoritative angle in degrees that we write back to the sprite
    # each frame. Tracked here rather than read from the sprite so the
    # ramp math is monotonic even across SFML quantization. Subsequent
    # icon taps don't reset @rolling_start — once we're rolling, we
    # stay rolling.
    @rolling_start   = nil
    @icon_angle      = 0.0
    # Particle pool. @dust holds active particles as hashes
    # {sprite:, born_at:, vx:, vy:}; @dust_bitmap is the shared GPU
    # texture every sprite references — built once in [build_dust_pool].
    @dust            = nil
    @dust_bitmap     = nil
    # @frame value of the last touch-moved spawn — throttles trailing
    # dust to one spawn per frame regardless of touch event rate.
    @last_dust_frame = -1

    # External-status polling state. We re-read STATUS_FILE_PATH every
    # STATUS_POLL_INTERVAL_F frames and update @status_text iff the
    # contents (or mtime) changed. -infinity-initial frame forces a poll
    # on the very first tick so any status the activity wrote BEFORE the
    # loader finished initializing is picked up immediately.
    @last_status_poll_frame = -STATUS_POLL_INTERVAL_F
    @last_status_mtime      = nil
    @last_status_payload    = nil
    # Touch events arrive in surface-pixel coordinates; the icon is laid
    # out in logical canvas units. Compute the per-axis scale once and
    # cache it, since the surface size doesn't change while the loader
    # runs (orientation change tears the activity down).
    dw = (LiteRGSS::DisplayWindow.desktop_width  rescue 0).to_i
    dh = (LiteRGSS::DisplayWindow.desktop_height rescue 0).to_i
    @touch_scale_x = dw > 0 ? @window_width.to_f  / dw : 1.0
    @touch_scale_y = dh > 0 ? @window_height.to_f / dh : 1.0
    load_project_info
    @icon_color = load_icon_color
  end

  def show!
    ensure_font_loaded
    open_window
    build_viewport
    build_dust_pool
    build_background
    build_particles
    build_icon
    build_text_layer
    # Anchor wall-clock AFTER all build_* allocations — first-frame work
    # is heavy (gradient gen, glow falloff). Starting the clock here
    # means the intro reveals at the expected real-world pace regardless
    # of cold-start cost.
    @wall_start_at = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  end

  # Pump one animation frame. Returns false if the window has been
  # closed (e.g. user dismissed it) — callers should bail.
  #
  # When the activity is paused (phone locked / app backgrounded), the
  # GL surface gets destroyed under us. `window.update` draws BEFORE
  # processing events (see DisplayWindowInput.cpp:213), so once focus is
  # lost we route through `update_only_input` instead — that path skips
  # the draw and blocks in waitEvent (because on_lost_focus flipped
  # polling=false) until focus comes back. Prevents the SIGSEGV from
  # SFML touching a dead EGLSurface.
  def tick
    return false unless @vp
    return false if @window.nil? || @window_closed
    if @has_focus
      # Wall-clock-derive @frame so animations track real time, not tick
      # count. After a GVL hold (compile interpreter monopolizing the
      # lock), @dframe jumps to "however many frames we missed"; the
      # integrators below scale by it so positions/angles end up where
      # they would have been if we'd ticked every 1/60s. Subsequent
      # ticks resume normal cadence with @dframe ≈ 1. fade-out uses
      # @frame, intro reveals use @frame, sine-driven breath/twinkle/
      # halo all use @frame absolute — all benefit automatically.
      now = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      prev_frame = @frame
      @frame = ((now - @wall_start_at) * FRAME_RATE).to_i
      # max() guards against the rare case where the monotonic clock
      # didn't advance enough to bump the integer floor between two
      # ticks (fast vsync, ratio rounding). dframe < 0 is impossible by
      # CLOCK_MONOTONIC contract, but defensive anyway.
      @dframe = [@frame - prev_frame, 0].max
      animate
      @window.update
      unless @first_frame_signaled
        @first_frame_signaled = true
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

  # Tick for `seconds` real-time seconds (rounded to the nearest frame at
  # FRAME_RATE). Returns false early if the underlying window closes —
  # callers should bail in that case, same contract as [tick].
  def hold_seconds(seconds)
    (seconds * FRAME_RATE).round.times { return false unless tick }
    true
  end

  # Swap the status text and hold long enough for the user to read it.
  def set_status(text)
    return unless @status_text && !@status_text.disposed?
    @status_text.text = text
    STATUS_HOLD.times { return unless tick }
  end

  def fade_out_and_dispose
    @fade_out_start = @frame
    @fade_out_frames.times { break unless tick }
    dispose
  end

  def dispose
    # Dust first: every dust sprite references the SHARED @dust_bitmap.
    # We dispose the sprites, then drop the shared bitmap exactly once
    # below — never via the per-sprite "dispose its bitmap" path used by
    # the unique-bitmap sprites in the generic loop.
    if @dust
      @dust.each { |p| p[:sprite].dispose unless p[:sprite].disposed? }
      @dust = nil
    end
    if @dust_bitmap && !@dust_bitmap.disposed?
      @dust_bitmap.dispose
    end
    @dust_bitmap = nil

    sprites = [@bg_sprite, @icon_glow, @icon].compact
    sprites.concat(@particles) if @particles
    sprites.each do |s|
      b = (s.respond_to?(:bitmap) ? s.bitmap : nil)
      b.dispose if b && !b.disposed?
      s.dispose unless s.disposed?
    end
    [@title_text, @version_text, @status_text, @footer_text].each do |t|
      t.dispose if t && !t.disposed?
    end
    @vp.dispose if @vp && !@vp.disposed?
    @vp = nil
    @window.dispose if @window
    @window = nil
  end

  private

  def format_version(n)
    [n].pack('I>').unpack('C*').join('.').gsub(/^(0\.)+/, '')
  end

  def load_project_info
    require 'json'
    raw = File.read(INFOS_CONFIG_PATH)
    info = JSON.parse(raw, symbolize_names: true)
    @title = info[:gameTitle].to_s.empty? ? 'PSDK Game' : info[:gameTitle].to_s
    @version = "v#{format_version(info[:gameVersion])}" if info[:gameVersion]
  rescue StandardError => e
    loader_say "infos_config: #{e.class}: #{e.message}"
    @title = 'PSDK Game'
    @version = ''
  end

  # Median RGB triple written by extract_loader_assets.rb's
  # detect_icon_color. Falls back to DEFAULT_ICON_COLOR if the file is
  # missing (extraction skipped on an all-transparent icon) or
  # malformed.
  def load_icon_color
    require 'json'
    raw = File.read(ICON_COLOR_PATH)
    c = JSON.parse(raw, symbolize_names: true)
    [
      c[:r].to_i.clamp(0, 255),
      c[:g].to_i.clamp(0, 255),
      c[:b].to_i.clamp(0, 255),
    ]
  rescue StandardError => e
    loader_say "icon_color: #{e.class}: #{e.message}"
    DEFAULT_ICON_COLOR.dup
  end

  # LiteRGSS::Text raises if its font id isn't registered. PSDK normally
  # walks Configs.texts.fonts at Graphics.on_start — we don't have that
  # yet, so register the well-known PSDK default ourselves.
  def ensure_font_loaded
    return unless File.exist?(@font_path)
    LiteRGSS::Fonts.load_font(0, @font_path)
    LiteRGSS::Fonts.set_default_size(0, 13)
    @font_loaded = true
  rescue StandardError => e
    loader_say "font load failed: #{e.class}: #{e.message}"
  end

  # Pin the short edge to LOGICAL_SHORT_EDGE and scale the long edge by
  # the surface aspect ratio. Querying DisplayWindow.desktop_* before
  # opening the window is supported (singleton method on the C++ side —
  # see DisplayWindow.cpp:581) and is the only orientation signal we
  # have at this point. Falls back to PSDK's classic 320x240 landscape
  # if SFML reports nonsense (e.g. before the surface is attached).
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

  def open_window
    # Args: title, width, height, scale, bpp, framerate, vsync, fullscreen, mouse_visible
    @window = LiteRGSS::DisplayWindow.new(
      @title, @window_width, @window_height, WINDOW_SCALE, 32, 60, true, false, false
    )
    # litergss2 has no should_close? — the close signal comes via this
    # callback. Returning truthy lets the C side raise rb_eClosedWindow,
    # which is the right behavior for a real Closed event (user-driven
    # window close). Android activity teardown no longer flows through
    # here — surface destroy/recreate uses SFML's LostFocus/GainedFocus
    # path (see on_lost_focus below), and terminal shutdown uses
    # $rgss_should_stop from CompileWizardActivity.onDestroy.
    @window.on_closed = proc { @window_closed = true; next true }
    # PSDK's Android pause-resume strategy (Graphics.rb on_lost_focus
    # branch): flip polling so the next event pump blocks in waitEvent
    # instead of busy-looping with pollEvent. The @has_focus flag is
    # what `tick` reads to skip drawing entirely while the surface is
    # gone.
    @window.on_lost_focus = proc do
      @has_focus = false
      @window.polling = false if @window
    end
    @window.on_gained_focus = proc do
      @has_focus = true
      @window.polling = true if @window
    end
    # Touch interaction:
    #   began  – spawn stardust at contact; if the hit lands on the icon,
    #            remember the finger id and start the press animation.
    #   moved  – spawn a single dust sparkle per frame as the finger drags
    #            (Android can deliver touch events faster than the splash
    #            renders, so we rate-limit by @frame).
    #   ended  – if the released finger is the same one that pressed the
    #            icon: spawn the radial "bang" at icon center, kick off
    #            the shake, and emit ICON_TAPPED_SENTINEL so the activity
    #            advances. The activity is still the arbiter (archive
    #            must be validated, step must be 0) — the loader only
    #            fires the event.
    @window.on_touch_began = proc do |finger, tx, ty|
      handle_touch_began(finger, tx, ty)
    end
    @window.on_touch_moved = proc do |_finger, tx, ty|
      handle_touch_moved(tx, ty)
    end
    @window.on_touch_ended = proc do |finger, tx, ty|
      handle_touch_ended(finger, tx, ty)
    end
  end

  def handle_touch_began(finger, tx, ty)
    lx = tx * @touch_scale_x
    ly = ty * @touch_scale_y
    spawn_dust(lx, ly, DUST_TAP_COUNT)
    return unless icon_hit?(lx, ly)
    return if @pressed_finger   # already held by another finger; ignore the new one
    @pressed_finger = finger
  end

  def handle_touch_moved(tx, ty)
    return if @last_dust_frame == @frame
    @last_dust_frame = @frame
    spawn_dust(tx * @touch_scale_x, ty * @touch_scale_y, DUST_MOVE_COUNT)
  end

  def handle_touch_ended(finger, tx, ty)
    lx = tx * @touch_scale_x
    ly = ty * @touch_scale_y
    if @pressed_finger == finger
      @pressed_finger = nil
      spawn_bang(@icon_cx, @icon_cy)
      @shake_start = @frame
      # First tap kicks off the rolling animation; later taps keep the
      # same spin start so the speed ramp doesn't visibly reset.
      @rolling_start ||= @frame
      loader_say ICON_TAPPED_SENTINEL
    else
      spawn_dust(lx, ly, DUST_RELEASE_COUNT)
    end
  end

  def icon_hit?(lx, ly)
    return false unless @icon_hit_rect
    rx, ry, rw, rh = @icon_hit_rect
    lx >= rx && lx < rx + rw && ly >= ry && ly < ry + rh
  end

  def build_viewport
    # litergss2's Viewport ctor REQUIRES a DisplayWindow as first arg
    # (Viewport.cpp:47). litergss3 made it optional via a process-global
    # window lookup, but on Android we're on the older API.
    @vp = LiteRGSS::Viewport.new(@window, 0, 0, @window.width, @window.height)
    @vp.z = 1_000_000
  end

  # Stardust bitmap shared by every sparkle sprite. Soft circular falloff
  # in cool-white (220, 230, 255) — the same hue family as the ambient
  # twinkles in build_particles, so the dust reads as "small star" rather
  # than as a separate UI element. Holding one bitmap and re-pointing
  # every sprite at it is the cheap-allocation path: spawning a sprite no
  # longer touches the GPU texture cache.
  def build_dust_pool
    img = LiteRGSS::Image.new(DUST_BITMAP_SIZE, DUST_BITMAP_SIZE)
    half = (DUST_BITMAP_SIZE - 1) / 2.0
    DUST_BITMAP_SIZE.times do |y|
      DUST_BITMAP_SIZE.times do |x|
        dx = x - half
        dy = y - half
        d = Math.sqrt(dx * dx + dy * dy) / [half, 1.0].max
        next if d >= 1.0
        a = ((1.0 - d) ** 2 * 255).to_i.clamp(0, 255)
        img.set_pixel(x, y, LiteRGSS::Color.new(220, 230, 255, a))
      end
    end
    @dust_bitmap = LiteRGSS::Bitmap.new(DUST_BITMAP_SIZE, DUST_BITMAP_SIZE)
    img.copy_to_bitmap(@dust_bitmap)
    img.dispose
    @dust = []
  end

  # Spawn `count` short-lived sparkles centered at (lx, ly) in logical
  # canvas coords. Velocities are randomized in a small cone; each
  # particle is born with @frame so [animate_dust] can age it by frame
  # delta rather than by wall-clock.
  def spawn_dust(lx, ly, count)
    return unless @dust && @dust_bitmap && @vp
    count.times do
      break if @dust.size >= MAX_DUST
      # Slight upward bias: feels like the dust is being lifted by the
      # finger rather than falling out of it.
      vx = (rand - 0.5) * 3.4
      vy = (rand - 0.5) * 3.0 - 0.6
      zoom = 0.9 + rand * 1.4
      @dust << make_dust_particle(lx, ly, vx, vy, zoom)
    end
  end

  # Radial "bang": BANG_COUNT particles fired outward in evenly-spaced
  # directions with mild jitter. Used on release of the icon — pairs with
  # the shake to read as "logo just emitted compile energy".
  def spawn_bang(cx, cy)
    return unless @dust && @dust_bitmap && @vp
    BANG_COUNT.times do |i|
      break if @dust.size >= MAX_DUST
      angle = (i.to_f / BANG_COUNT) * 2.0 * Math::PI + (rand - 0.5) * 0.25
      speed = BANG_SPEED_MIN + rand * (BANG_SPEED_MAX - BANG_SPEED_MIN)
      vx = Math.cos(angle) * speed
      vy = Math.sin(angle) * speed
      zoom = 1.2 + rand * 0.8
      @dust << make_dust_particle(cx, cy, vx, vy, zoom)
    end
  end

  # Create one dust sprite and the bookkeeping hash that owns its
  # animated state. The hash carries position, velocity AND zoom because
  # LiteRGSS::Sprite exposes `zoom=` only — there's no `zoom` reader, so
  # we can't round-trip the current value through the sprite each frame.
  # Position has getters (`x`, `y`), so it stays sprite-resident.
  def make_dust_particle(lx, ly, vx, vy, zoom)
    s = LiteRGSS::Sprite.new(@vp)
    s.bitmap = @dust_bitmap
    s.set_origin(DUST_BITMAP_SIZE / 2, DUST_BITMAP_SIZE / 2)
    s.set_position(lx.to_i, ly.to_i)
    s.opacity = 255
    s.zoom = zoom
    s.z = 30
    { sprite: s, born_at: @frame, vx: vx, vy: vy, zoom: zoom }
  end

  def build_background
    w = @vp.rect.width
    h = @vp.rect.height
    top_r, top_g, top_b, bot_r, bot_g, bot_b = gradient_endpoints
    img = LiteRGSS::Image.new(w, h)
    # Vertical gradient: icon-tinted dark at top → near-black at bottom.
    h.times do |y|
      t = y.to_f / [h - 1, 1].max
      r = (top_r * (1.0 - t) + bot_r * t).to_i
      g = (top_g * (1.0 - t) + bot_g * t).to_i
      b = (top_b * (1.0 - t) + bot_b * t).to_i
      img.fill_rect(0, y, w, 1, LiteRGSS::Color.new(r, g, b, 255))
    end
    @bg_sprite = LiteRGSS::Sprite.new(@vp)
    # Sprite#bitmap= requires a LiteRGSS::Bitmap (GPU texture); assigning
    # an Image silently sets the sprite invisible — that was the bug
    # behind the blank loader window in the first attempt on litergss2.
    @bg_sprite.bitmap = LiteRGSS::Bitmap.new(w, h)
    img.copy_to_bitmap(@bg_sprite.bitmap)
    img.dispose
    @bg_sprite.z = 0
  end

  # Derives gradient endpoints from @icon_color. The top color is
  # peak-normalized so its brightest channel always sits at GRADIENT_TOP_PEAK
  # regardless of how dark or saturated the icon is — keeps the gradient
  # visible for very dark icons while preserving the icon's hue. The
  # bottom color uses the same hue scaled to GRADIENT_BOT_PEAK so the
  # gradient stays a single chromatic family (no "purple-to-green" weirdness).
  GRADIENT_TOP_PEAK = 56
  GRADIENT_BOT_PEAK = 11
  def gradient_endpoints
    ir, ig, ib = @icon_color
    peak = [ir, ig, ib, 1].max
    top_scale = GRADIENT_TOP_PEAK.to_f / peak
    bot_scale = GRADIENT_BOT_PEAK.to_f / peak
    [
      (ir * top_scale).to_i, (ig * top_scale).to_i, (ib * top_scale).to_i,
      (ir * bot_scale).to_i, (ig * bot_scale).to_i, (ib * bot_scale).to_i,
    ]
  end

  # Tiny drifting "stars" — gives the screen low-frequency motion.
  def build_particles
    src = LiteRGSS::Image.new(2, 2)
    src.fill_rect(0, 0, 2, 2, LiteRGSS::Color.new(220, 230, 255, 255))
    w = @vp.rect.width
    h = @vp.rect.height
    @particles = PARTICLE_COUNT.times.map do |i|
      bmp = LiteRGSS::Bitmap.new(2, 2)
      src.copy_to_bitmap(bmp)
      s = LiteRGSS::Sprite.new(@vp)
      s.bitmap = bmp
      s.z = 1
      s.set_position(((i + 1) * 37 + 11) % w, ((i * 53) + 19) % h)
      s.opacity = 40 + ((i * 23) % 60)
      s.zoom = 0.5 + ((i % 3) * 0.4)
      s
    end
    src.dispose
  end

  def build_icon
    cx = @icon_cx
    cy = @icon_cy

    # Soft additive-looking radial halo behind the icon. Tinted with the
    # icon's median color so the halo and gradient share a hue family —
    # a generic blue halo on a warm icon used to feel disconnected once
    # the splash became the activity centerpiece.
    glow_color = halo_color
    g = LiteRGSS::Image.new(GLOW_SIZE, GLOW_SIZE)
    r_outer = GLOW_SIZE / 2.0
    half = GLOW_SIZE / 2
    GLOW_SIZE.times do |y|
      dy = y - half
      GLOW_SIZE.times do |x|
        dx = x - half
        d = Math.sqrt(dx * dx + dy * dy) / r_outer
        next if d >= 1.0
        a = ((1.0 - d) ** 3 * 150).to_i
        g.set_pixel(x, y, LiteRGSS::Color.new(glow_color[0], glow_color[1], glow_color[2], a))
      end
    end
    @icon_glow = LiteRGSS::Sprite.new(@vp)
    @icon_glow.bitmap = LiteRGSS::Bitmap.new(GLOW_SIZE, GLOW_SIZE)
    g.copy_to_bitmap(@icon_glow.bitmap)
    g.dispose
    @icon_glow.set_origin(GLOW_SIZE / 2, GLOW_SIZE / 2)
    @icon_glow.set_position(cx, cy)
    @icon_glow.opacity = 0
    @icon_glow.z = 5

    icon_bmp = load_icon_bitmap
    return unless icon_bmp

    @icon_w = icon_bmp.width
    @icon_h = icon_bmp.height

    @icon = LiteRGSS::Sprite.new(@vp)
    @icon.bitmap = icon_bmp
    @icon.set_origin(@icon_w / 2, @icon_h / 2)
    @icon.set_position(cx, cy)
    @icon.opacity = 0
    @icon.zoom = 0.4
    @icon.z = 10

    # Hit rect for the touch handler — anchored at the icon's steady-state
    # size (zoom=1.0) rather than the intro starting zoom of 0.4, so users
    # who tap during the intro fade-in still land on the target. The actual
    # sprite breathes within ±0.03 zoom; the padding swallows that.
    @icon_hit_rect = [
      cx - @icon_w / 2 - ICON_HIT_PADDING,
      cy - @icon_h / 2 - ICON_HIT_PADDING,
      @icon_w + 2 * ICON_HIT_PADDING,
      @icon_h + 2 * ICON_HIT_PADDING,
    ]
  end

  # Halo color: peak-normalize the icon's median color toward HALO_PEAK so
  # the halo always reads as "bright glow" regardless of how dark the icon
  # is. Same idea as gradient_endpoints, different target luminance.
  HALO_PEAK = 220
  def halo_color
    ir, ig, ib = @icon_color
    peak = [ir, ig, ib, 1].max
    s = HALO_PEAK.to_f / peak
    [
      (ir * s).to_i.clamp(0, 255),
      (ig * s).to_i.clamp(0, 255),
      (ib * s).to_i.clamp(0, 255),
    ]
  end

  # CTA accent: same normalization with a slightly lower peak — the CTA
  # text reads cleaner at ~200 than at full halo brightness, and the
  # outline shadow gives it enough pop on top of the gradient.
  CTA_PEAK = 215
  def cta_accent_color
    ir, ig, ib = @icon_color
    peak = [ir, ig, ib, 1].max
    s = CTA_PEAK.to_f / peak
    [
      (ir * s).to_i.clamp(0, 255),
      (ig * s).to_i.clamp(0, 255),
      (ib * s).to_i.clamp(0, 255),
    ]
  end

  def load_icon_bitmap
    data = File.read(ICON_PATH)
    tmp = LiteRGSS::Image.new(data, true)
    out = LiteRGSS::Bitmap.new(tmp.width, tmp.height)
    tmp.copy_to_bitmap(out)
    tmp.dispose
    out
  rescue StandardError => e
    loader_say "icon load failed: #{e.class}: #{e.message}"
    nil
  end

  # Caption-style stack centered on the icon:
  #
  #   [Game Title]            ← TITLE_FONT_SIZE, white, full outline
  #     v1.2.3                ← VERSION_FONT_SIZE, icon-tinted, soft outline
  #     [ICON]                ← centered on canvas
  #   ‹tap logo to compile›   ← CTA_FONT_SIZE, accent-tinted, hugs icon
  #            …
  #   Powered by PSDK · …     ← FOOTER_FONT_SIZE, anchored to canvas bottom
  #
  # All y positions are derived from @icon_cy plus/minus @icon_h/2 so the
  # whole stack tracks the icon when it sits at a different vertical
  # position (e.g. in a future orientation rebalance).
  def build_text_layer
    return unless @font_loaded   # without a font, Text.new raises
    cy = @icon_cy
    half_h = @icon_h / 2
    full_w = @window_width
    accent = cta_accent_color

    # Visual hierarchy from font size: title largest, footer smallest.
    # The CTA sits between version and footer in weight — readable but
    # subordinate to the title.
    # Title box grew with TITLE_FONT_SIZE (16 → 20); offset bumped by the
    # same 4 px so version_y still sits 4 px below the title's bottom edge.
    title_y   = cy - half_h - 60
    version_y = cy - half_h - 28
    cta_y     = cy + half_h + 8
    footer_y  = @window_height - 22

    @title_text = LiteRGSS::Text.new(0, @vp, 0, title_y, full_w, 28, @title, 1)
    @title_text.size = TITLE_FONT_SIZE
    @title_text.fill_color        = LiteRGSS::Color.new(245, 245, 250, 255)
    @title_text.outline_color     = LiteRGSS::Color.new(0, 0, 0, 220)
    @title_text.outline_thickness = 2
    @title_text.opacity = 0
    @title_text.z = 20

    @version_text = LiteRGSS::Text.new(0, @vp, 0, version_y, full_w, 18, @version, 1)
    @version_text.size = VERSION_FONT_SIZE
    @version_text.fill_color    = LiteRGSS::Color.new(accent[0], accent[1], accent[2], 230)
    @version_text.outline_color = LiteRGSS::Color.new(0, 0, 0, 180)
    @version_text.opacity = 0
    @version_text.z = 20

    # Caption-style CTA. "‹" and "›" are common single-guillemet glyphs;
    # they fall back to a missing-glyph box on fonts without them. The
    # PokemonDS.ttf shipped by most PSDK projects covers them. If we ever
    # ship a font without them, drop the brackets — the message stands
    # on its own.
    @status_text = LiteRGSS::Text.new(0, @vp, 0, cta_y, full_w, 16,
                                      "‹ Initializing… ›", 1)
    @status_text.size = CTA_FONT_SIZE
    @status_text.fill_color        = LiteRGSS::Color.new(accent[0], accent[1], accent[2], 255)
    @status_text.outline_color     = LiteRGSS::Color.new(0, 0, 0, 220)
    @status_text.outline_thickness = 1
    @status_text.opacity = 0
    @status_text.z = 20

    @footer_text = LiteRGSS::Text.new(0, @vp, 0, footer_y, full_w, 12,
                                      'Powered by PSDK · LiteRGSS2 · Ruby VM', 1)
    @footer_text.size = FOOTER_FONT_SIZE
    @footer_text.fill_color    = LiteRGSS::Color.new(110, 125, 150, 200)
    @footer_text.outline_color = LiteRGSS::Color.new(0, 0, 0, 140)
    @footer_text.opacity = 0
    @footer_text.z = 20
  end

  # Per-frame animator. Splits cleanly into intro (frame 0..INTRO),
  # steady (frame > INTRO), and outro (frame >= @fade_out_start).
  def animate
    multiplier = fade_out_multiplier
    animate_icon(multiplier)
    animate_text_layer(multiplier)
    animate_particles(multiplier)
    animate_dust(multiplier)
    poll_external_status
  end

  # Read a one-line status from STATUS_FILE_PATH (written by the compile
  # wizard during compilation) and swap it into @status_text. mtime gates
  # the read so the steady-state cost is one File.mtime stat per
  # STATUS_POLL_INTERVAL_F frames — cheap on Android's ext4.
  #
  # Failures (file missing, permission flake, malformed bytes) leave the
  # previous status in place — same "best-effort" contract as the
  # Kotlin writer (see CompileProgressFragment#pushStatus).
  def poll_external_status
    return if (@frame - @last_status_poll_frame) < STATUS_POLL_INTERVAL_F
    @last_status_poll_frame = @frame
    return unless @status_text && !@status_text.disposed?
    begin
      return unless File.exist?(STATUS_FILE_PATH)
      mtime = File.mtime(STATUS_FILE_PATH)
      return if @last_status_mtime && mtime == @last_status_mtime
      @last_status_mtime = mtime
      payload = File.read(STATUS_FILE_PATH, STATUS_FILE_BYTE_LIMIT).to_s.strip
      return if payload.empty?
      return if payload == @last_status_payload
      @last_status_payload = payload
      @status_text.text = payload
    rescue StandardError => e
      loader_say "status poll: #{e.class}: #{e.message}"
    end
  end

  def fade_out_multiplier
    return 1.0 unless @fade_out_start
    progress = (@frame - @fade_out_start).to_f / @fade_out_frames
    [1.0 - ease_out_cubic(progress.clamp(0.0, 1.0)), 0.0].max
  end

  def animate_icon(m)
    intro = ease_out_cubic([@frame / 60.0, 1.0].min)
    base_zoom = 0.4 + 0.6 * intro
    breath = Math.sin(@frame * 0.09) * 0.03 * intro

    # Press: ease @press_factor toward 1.0 (pressed) or 0.0 (released).
    # The depression reduces zoom; breathing is scaled by (1 - factor)
    # so a fully pressed icon really does freeze.
    target_press   = @pressed_finger ? 1.0 : 0.0
    @press_factor += (target_press - @press_factor) * PRESS_SPRING
    press_z        = -PRESS_DEPRESSION * @press_factor
    breath        *= (1.0 - @press_factor)

    # Decaying release shake. SHAKE_FRAMES sets the window; once we're
    # past it the offset goes to 0 and @shake_start is cleared so we
    # don't burn cycles on dead state.
    shake_x = 0.0
    shake_y = 0.0
    if @shake_start
      sf = @frame - @shake_start
      if sf < SHAKE_FRAMES
        decay = 1.0 - sf.to_f / SHAKE_FRAMES
        shake_x = Math.sin(sf * 1.8) * SHAKE_AMPLITUDE * decay
        shake_y = Math.sin(sf * 2.3 + 0.7) * SHAKE_AMPLITUDE * 0.6 * decay
      else
        @shake_start = nil
      end
    end

    icon_x = (@icon_cx + shake_x).to_i
    icon_y = (@icon_cy + shake_y).to_i

    # Rolling spin: ramps from 0 to ROLL_SPEED_DEG_PER_FRAME over
    # ROLL_RAMP_FRAMES, then runs at steady state forever. The glow is
    # rotationally symmetric so only the icon sprite is rotated. Scaled
    # by @dframe so the spin tracks wall time across a GVL hold — after
    # a 500ms freeze, the angle snaps forward to where it would have
    # been instead of resuming behind real time.
    if @rolling_start
      age   = @frame - @rolling_start
      ramp  = ease_out_cubic([age.to_f / ROLL_RAMP_FRAMES, 1.0].min)
      step  = ROLL_SPEED_DEG_PER_FRAME * ROLL_DIRECTION * ramp * @dframe
      @icon_angle = (@icon_angle + step) % 360.0
    end

    if @icon && !@icon.disposed?
      @icon.zoom = base_zoom + breath + press_z
      @icon.opacity = (255 * intro * m).to_i
      @icon.set_position(icon_x, icon_y)
      @icon.angle = @icon_angle if @rolling_start
    end
    if @icon_glow && !@icon_glow.disposed?
      halo_pulse = 0.7 + 0.3 * Math.sin(@frame * 0.07)
      @icon_glow.zoom = (base_zoom + press_z) * 1.05 + breath * 0.5
      @icon_glow.opacity = (170 * intro * halo_pulse * m).to_i
      @icon_glow.set_position(icon_x, icon_y)
    end
  end

  def animate_text_layer(m)
    title_t = ease_out_cubic(((@frame - 24) / 44.0).clamp(0.0, 1.0))
    if @title_text && !@title_text.disposed?
      @title_text.opacity = (255 * title_t * m).to_i
    end

    ver_t = ease_out_cubic(((@frame - 44) / 44.0).clamp(0.0, 1.0))
    if @version_text && !@version_text.disposed?
      @version_text.opacity = (235 * ver_t * m).to_i
    end

    status_t = ease_out_cubic(((@frame - 60) / 36.0).clamp(0.0, 1.0))
    if @status_text && !@status_text.disposed?
      @status_text.opacity = (255 * status_t * m).to_i
    end

    footer_t = ease_out_cubic(((@frame - 84) / 56.0).clamp(0.0, 1.0))
    if @footer_text && !@footer_text.disposed?
      @footer_text.opacity = (220 * footer_t * m).to_i
    end
  end

  # Age and update every active dust particle. Expired ones get their
  # sprite disposed and removed from the pool in-place. The sprite's
  # bitmap is @dust_bitmap (shared) — we never dispose it here, only on
  # [dispose].
  def animate_dust(m)
    return unless @dust
    @dust.reject! do |p|
      age = @frame - p[:born_at]
      if age >= DUST_LIFE_FRAMES
        p[:sprite].dispose unless p[:sprite].disposed?
        next true
      end
      s = p[:sprite]
      next true if s.disposed?
      t = age.to_f / DUST_LIFE_FRAMES
      eased = 1.0 - (1.0 - t) ** 3
      # Move + damp velocity so the particles settle rather than fly off.
      # @dframe lets a single tick advance N "frames" worth of motion
      # after a GVL hold — uses the pre-damp velocity, which is a
      # rectangular-rule integration. Close enough at dust's short life
      # (DUST_LIFE_FRAMES = 36); any catch-up beyond ~half a second
      # just lands the particle past its lifetime and the next branch
      # disposes it anyway.
      df = @dframe
      s.set_position((s.x + p[:vx] * df).to_i, (s.y + p[:vy] * df).to_i)
      # Exponentiated damping is exact for any df ≥ 0: an N-step
      # geometric decay collapses to one ** call.
      decay = 0.94 ** df
      p[:vx] *= decay
      # Gravity accumulation: pre-decay vy plus df steps of +0.06 each
      # — same rectangular approximation as position. Drift error is
      # bounded by 0.06 * df² / 2, ~5 px after a 500ms freeze (df=30),
      # which dust at this size and life never reveals.
      p[:vy] = p[:vy] * decay + 0.06 * df
      s.opacity = ((1.0 - eased) * 255 * m).to_i.clamp(0, 255)
      # Zoom is hash-tracked (no Sprite#zoom getter in litergss2) —
      # write back to the sprite each frame from our authoritative copy.
      p[:zoom] = (p[:zoom] * (0.96 ** df)).clamp(0.1, 4.0)
      s.zoom = p[:zoom]
      false
    end
  end

  # Ambient star drift. Pre-tap: original 0.15..0.31 px/frame slow drift
  # (idle / "waiting for you" feel). Post-tap: scaled by
  # PARTICLE_SPEED_BOOST — paired with the rolling icon and the bang,
  # it reads as "warp-speed, we're working now". The transition eases
  # in over ROLL_RAMP_FRAMES on the same curve as the spin so both
  # effects feel like one coherent kickoff rather than two
  # independent triggers.
  PARTICLE_SPEED_BOOST = 50.0
  def animate_particles(m)
    w = @vp.rect.width
    h = @vp.rect.height
    boost = particle_speed_multiplier
    @particles.each_with_index do |s, i|
      next if s.disposed?
      # Per-frame drift scaled by @dframe — after a GVL hold the stars
      # land at the wall-time-correct y, modulo-wrapped exactly as if
      # we'd been ticking continuously. Twinkle phase is sine-of-@frame,
      # so it tracks automatically.
      speed = (0.15 + (i % 3) * 0.08) * boost
      y = (s.y - speed * @dframe) % (h + 4)
      y = h + 4 if y < -2
      s.y = y
      twinkle = (Math.sin(@frame * 0.05 + i * 1.3) + 1.0) / 2.0
      s.opacity = ((40 + 90 * twinkle) * m).to_i
    end
  end

  # 1.0 before the user taps the icon, ramps up to PARTICLE_SPEED_BOOST
  # over ROLL_RAMP_FRAMES once @rolling_start fires. Same ease curve as
  # the rolling angle ramp — see [animate_icon].
  def particle_speed_multiplier
    return 1.0 unless @rolling_start
    age  = @frame - @rolling_start
    ramp = ease_out_cubic([age.to_f / ROLL_RAMP_FRAMES, 1.0].min)
    1.0 + (PARTICLE_SPEED_BOOST - 1.0) * ramp
  end

  def ease_out_cubic(t)
    return 0.0 if t <= 0.0
    return 1.0 if t >= 1.0
    1.0 - ((1.0 - t) ** 3)
  end
end

# Open the splash, play the intro, then tick the animation forever.
# tick returns false once the underlying DisplayWindow is closed (the
# activity tears the loader interpreter down on destroy), which breaks
# the loop and lets `dispose` run cleanly.
$loader_screen = Loader.new
$loader_screen.show!
$loader_screen.run_intro

# Decorative status progression — the splash isn't gated on real compile
# state (the activity tears the loader interpreter down on destroy), so
# the CTA just means "intro finished, we've been on Initializing for a
# few seconds". hold_seconds + set_status return false if the window
# closes mid-hold, which propagates into the final tick loop bailing
# immediately. Copy intentionally mirrors the import card's removed
# "Start Compilation" button — the icon is now the tap target. The
# brackets match the caption styling in [build_text_layer].
if $loader_screen.hold_seconds(Loader::INIT_TO_READY_SECS)
  $loader_screen.set_status('‹ Tap logo to compile ›')
end

loop { break unless $loader_screen.tick }

$loader_screen&.dispose rescue nil
