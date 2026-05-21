# Standalone splash orchestrator.
#
# All implementation lives in loader/*.rb. The Android side can't
# resolve require_relative (the script is `ruby -e`'d via AssetManager
# String content, so __FILE__ is "-e"; the helper files aren't on the
# filesystem or in a PhysFS mount), so RubyScript concatenates the
# helpers as preludes before this file — see RootActivity.kt's
# LOADER_PRELUDES for the order. By the time this script runs, the
# whole Loader::* namespace is defined.
#
# Reads project metadata / icon / font from <pwd>/loader_assets/ —
# files extract_loader_assets.rb wrote during archive validation. That
# means this script has zero archive dependency: no PhysFS, no
# ArchiveMount, no EpsaStream. The mount is owned by check_engine.rb
# on the compile side.
#
# Lifecycle:
#   1. Loader::Splash.new + show! + run_intro.
#   2. Optional CTA flash (only if no real status arrived during the
#      intro hold).
#   3. Tick the animation forever, bailing when the window is closed
#      (e.g. activity destroyed) — the activity tears the loader
#      interpreter down by closing it.

# Procedural "nebula" background — slow, hypnotic flowing bands of
# light tinted by the project icon's hue, with a soft halo above the
# icon and sparse twinkles. Sits on top of the static gradient so the
# gradient remains the fallback if the shader fails to link on a given
# device. Hooks into the splash's existing layer list so animate() and
# dispose are driven automatically by the splash's tick loop and
# teardown.
class LoaderNebula
  def initialize(splash)
    @splash = splash
  end

  def build
    window   = @splash.instance_variable_get(:@window)
    viewport = window.viewport
    w        = viewport.rect.width
    h        = viewport.rect.height
    tint     = Loader::Assets.load_icon_color

    @sprite = LiteRGSS::ShaderedSprite.new(viewport)
    # The bitmap defines the sprite quad's geometry; the shader writes
    # pixels procedurally so the bitmap's contents are irrelevant.
    @sprite.bitmap = LiteRGSS::Bitmap.new(w, h)
    @sprite.x = 0
    @sprite.y = 0
    # z is a long in litergss2 (Sprite.cpp:103 — rb_num2long) so we
    # can't sit "between" the gradient (z=0) and particles/dust
    # (z=1). Equal z is broken by creation order, and this sprite is
    # built strictly AFTER show! has finished assembling the
    # standard layers — gradient is created first → drawn first
    # (behind); the nebula is created later → drawn next (on top of
    # the gradient); particles/dust at z=1 still draw above us.
    @sprite.z = 0
    # Start invisible — the first frame after build() runs with
    # u_time=0 and the GPU has only just received the shader; any
    # single-frame hiccup (driver doing async shader work, sprite
    # state not fully propagated, the bitmap upload landing late)
    # produces a full-screen artifact otherwise. The opacity ramp in
    # animate() fades the nebula in over the first second, which
    # also blends naturally with the existing intro animations on
    # the other layers.
    @sprite.opacity = 0

    @shader = LiteRGSS::Shader.new(VERT_SRC, FRAG_SRC)
    @shader.set_float_uniform('u_resolution', [w.to_f, h.to_f])
    @shader.set_float_uniform('u_tint', [tint[0] / 255.0, tint[1] / 255.0, tint[2] / 255.0])
    @shader.set_float_uniform('u_time', 0.0)
    @sprite.shader = @shader

    @build_frame = @splash.instance_variable_get(:@clock).frame
    layers = @splash.instance_variable_get(:@layers)
    layers << self if layers
  end

  # Splash#tick calls @layers.each { |l| l.animate(clock, state, m) }.
  # Driving u_time from clock.frame keeps the animation phase aligned
  # with everything else (icon spin, ambient drift) and survives GVL
  # pauses cleanly — clock.frame already accounts for dframe catch-up.
  # Opacity ramps 0→255 over the first FADE_IN_FRAMES after build, so
  # the nebula slides in under the icon/text reveal instead of
  # snapping on the first post-build draw.
  FADE_IN_FRAMES = 60

  def animate(clock, _state, _m)
    return unless @shader
    age = clock.frame - (@build_frame || 0)
    ramp = (age.to_f / FADE_IN_FRAMES).clamp(0.0, 1.0)
    @sprite.opacity = (255 * Loader::Util.ease_out_cubic(ramp)).to_i
    @shader.set_float_uniform('u_time', clock.frame / Loader::Clock::FRAME_RATE.to_f)
  end

  def dispose
    @sprite.bitmap.dispose if @sprite && @sprite.bitmap && !@sprite.bitmap.disposed?
    @sprite.dispose if @sprite && !@sprite.disposed?
    @sprite = nil
    @shader = nil
  end

  # Explicit vertex shader: SFML's built-in default vert doesn't
  # declare the varyings LiteCGSS's ShaderPreprocessor injects into
  # every fragment shader on GLES (v_texture_coordinates,
  # v_factor_npot, sf_color, …). Without matching varyings on the
  # vertex side, the GLES program silently fails to link and falls
  # back to the default sampler — same trap the earlier smoke test hit
  # when it shipped a fragment-only shader.
  VERT_SRC = <<~VERT
    #ifdef GL_ES
    uniform mat4 sf_modelview;
    uniform mat4 sf_projection;
    uniform mat4 sf_texture;
    uniform vec2 factor_npot;
    attribute vec2 position;
    attribute vec2 texCoord;
    attribute vec4 color;
    varying vec4 sf_color;
    varying vec2 v_texture_coordinates;
    varying vec2 v_inv_texture_coordinates;
    varying vec2 v_factor_npot;
    #else
    attribute vec2 position;
    uniform mat4 sf_modelview;
    uniform mat4 sf_projection;
    #endif
    void main() {
    #ifdef GL_ES
      sf_color = color;
      v_texture_coordinates = (sf_texture * vec4(texCoord, 0.0, 1.0)).xy;
      v_factor_npot = factor_npot;
    #endif
      gl_Position = sf_projection * sf_modelview * vec4(position.xy, 0.0, 1.0);
    }
  VERT

  # Stacked-sine aurora — ~5 transcendentals per pixel total, vs the
  # ~24 a value-noise FBM would burn. The earlier FBM version pushed
  # the Adreno over the 16.6ms budget and produced QueueBuffer
  # timeouts (frames piling up faster than they could be presented),
  # which froze the rest of the splash's animation visually. Two
  # phase-modulated sin layers produce flowing band shapes that read
  # as aurora at this scale.
  #
  # Do NOT declare `precision mediump float;` or `varying vec2
  # v_texture_coordinates;` here — LiteCGSS's ShaderPreprocessor
  # injects both (along with v_inv_texture_coordinates, sf_color,
  # v_factor_npot) into every fragment shader. Redeclaring them
  # triggers a GLSL "redefinition" error on Adreno. The vertex shader
  # is NOT preprocessed, so the varyings on that side still have to
  # be declared explicitly.
  FRAG_SRC = <<~FRAG
    uniform float u_time;
    uniform vec3  u_tint;
    uniform vec2  u_resolution;

    void main() {
      vec2 uv = v_texture_coordinates;
      vec2 aspect = vec2(u_resolution.x / max(u_resolution.y, 1.0), 1.0);
      vec2 p = (uv - 0.5) * aspect;

      float t = u_time * 0.08;

      // Two phase-modulated sine layers. Feeding the second's phase
      // through the first produces curved, flowing bands instead of
      // straight stripes — cheap domain warping without a noise
      // texture. The wide frequency spread (2.7 vs 1.3) keeps the
      // result from looking like a regular interference pattern.
      float w1 = sin(p.x * 2.7 - t * 1.3 + sin(p.y * 1.4 + t * 0.6) * 1.7);
      float w2 = sin(p.y * 2.1 + t * 0.9 + cos(p.x * 1.6 - t * 0.5) * 1.2);
      float bands = smoothstep(0.10, 0.95, (w1 + w2 * 0.7) * 0.5 + 0.5);

      // Mirrors the static Background layer's top-brighter /
      // bottom-near-black falloff so the nebula reads as a
      // continuation of the background rather than an overlay.
      float vgrad = mix(0.28, 0.04, smoothstep(0.0, 1.0, uv.y));

      // Soft halo where the icon sits (slightly above middle) —
      // gives the icon a "pool of light" without a bloom pass.
      vec2  hc   = vec2(0.5, 0.42);
      float r    = length((uv - hc) * aspect);
      float halo = exp(-r * r * 6.0) * 0.30;

      // Slow hue shift between the icon tint and its swizzled
      // complement — chromatic motion that never leaves the icon's
      // color family.
      vec3 hue_b = mix(u_tint, u_tint.gbr, 0.6);
      vec3 hue   = mix(u_tint, hue_b, 0.5 + 0.5 * sin(t * 1.7 + w1));

      vec3 col = hue * (vgrad + bands * 0.55 + halo);

      // Cinematic vignette — anchors the eye toward the icon halo.
      float vig = smoothstep(1.05, 0.40, length((uv - 0.5) * aspect));
      col *= mix(0.55, 1.0, vig);

      gl_FragColor = vec4(col, 1.0);
    }
  FRAG
end

$loader_screen = Loader::Splash.new

# `ensure` is load-bearing AND must wrap the ENTIRE post-construction
# body, not just the final loop. When the activity drives a workload
# swap (project click → setLoaderState restart), it pushes
# sf::Event::Closed into the loader's DisplayWindow queue. Any of the
# update-driving calls below (show!, run_intro, hold_seconds, tick)
# will raise LiteRGSS::DisplayWindow::ClosedWindowError from inside
# `update` — including the hold_seconds in the splash's pre-loop CTA
# logic. If only the loop were wrapped, an exception raised during
# hold_seconds would unwind past the begin (never entering it) and
# $loader_screen would leak to KMP's destroyInterpreter on the UI
# thread. The destructor would then call setActive(false) on the
# wrong thread (UI, not the Ruby render thread that owns the EGL
# context) — releasing nothing, leaving the context bound to the
# dying render thread, causing the next loader's render thread to
# loop forever on EGL_BAD_ACCESS.
#
# With this wider ensure, dispose runs on THIS render thread mid-
# unwind regardless of which call raised. setActive(false) executes
# on the context-owning thread, EGL releases cleanly, the log-flush
# sentinel arrives promptly, and destroyInterpreter returns instead
# of timing out at 5s (which is what was producing the ANR).
begin
  $loader_screen.show!

  # Render one frame of the standard layers (gradient, particles, icon,
  # text) BEFORE building the nebula. LiteRGSS::Shader.new compiles &
  # links the GLSL synchronously on Adreno, which can stall the calling
  # thread for 100–500ms cold. If we hold up the very first
  # @window.update, the [loader-ready] sentinel that gates RootActivity's
  # SurfaceView fade-in is delayed too — and the surface stays at
  # alpha=0 until the next compositor wake (which, in practice, is the
  # user's first touch, since the render thread isn't producing
  # anything new for the compositor to latch). Decoupling the shader
  # compile from the first present cleanly fixes the "screen stays
  # blank until I tap" symptom while still letting the nebula appear
  # within ~one extra frame of the standard intro.
  $loader_screen.tick
  begin
    LoaderNebula.new($loader_screen).build
  rescue => e
    STDERR.puts "[loader.rb] Nebula shader setup failed: #{e.class}: #{e.message}"
    STDERR.puts e.backtrace.first(5).join("\n")
  end

  $loader_screen.run_intro

  # Decorative status progression — the splash isn't gated on real
  # compile state (the activity tears the loader interpreter down on
  # destroy), so the CTA just means "intro finished, we've been on
  # Initializing for a few seconds". hold_seconds returns false if the
  # window closes mid-hold, which propagates into the final tick loop
  # bailing immediately. Copy intentionally mirrors the import card's
  # removed "Start Compilation" button — the icon is now the tap target.
  #
  # Gated on external_status_received? so the CTA never clobbers a real
  # sub-step label. Classic-bootstrap's auto-tap fires before this point
  # and the compile engine's first onStepStarted ("Check engine…")
  # arrives via the .loader_status poll inside hold_seconds, which sets
  # the flag — so the CTA is skipped entirely in classic mode. Same
  # protection covers fast super-user taps where the user races the
  # 3-second hold.
  if $loader_screen.hold_seconds(Loader::Splash::INIT_TO_READY_SECS) &&
     !$loader_screen.external_status_received?
    $loader_screen.set_status('< Tap logo to compile >')
  end

  loop { break unless $loader_screen.tick }
ensure
  $loader_screen&.dispose rescue nil
  PSDKVMSnapshot.log_diff!
  PSDKVMSnapshot.restore!
end
