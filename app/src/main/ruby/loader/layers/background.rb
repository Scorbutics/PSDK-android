module Loader
  module Layers
    # Vertical gradient: icon-tinted dark at top → near-black at bottom.
    # The top color is peak-normalized so its brightest channel always
    # sits at GRADIENT_TOP_PEAK regardless of how dark or saturated the
    # icon is — keeps the gradient visible for very dark icons while
    # preserving the icon's hue. The bottom color uses the same hue
    # scaled to GRADIENT_BOT_PEAK so the gradient stays a single
    # chromatic family (no "purple-to-green" weirdness).
    #
    # Optionally animated by a "nebula" fragment shader (flowing
    # aurora-like bands of light in the icon's hue). The shader is
    # NOT attached during build() — splash.rb's orchestrator calls
    # #enhance_with_nebula! after run_intro completes, so the standard
    # gradient renders unmodified for the first ~2 seconds. That
    # ordering matters on MIUI / HyperOS: introducing a fresh
    # Shader.new + ShaderedSprite during the same window as the
    # FIRST surface presentation puts that compositor's layer-
    # visibility check into a state where the layer stays invisible
    # until a system-level input event arrives, and we have no way
    # from a normal app to synthesize one (INJECT_EVENTS is system-
    # signed). Deferring the shader to AFTER the splash is on screen
    # cleanly sidesteps that path.
    class Background
      GRADIENT_TOP_PEAK = 56
      GRADIENT_BOT_PEAK = 11

      # Frames to ramp the nebula effect in from "pure gradient" to
      # full strength. Eased-out cubic, so it lingers slightly toward
      # the end instead of snapping in at the last frame.
      NEBULA_FADE_IN_FRAMES = 60

      def initialize(icon_color)
        @icon_color = icon_color
      end

      def build(viewport)
        w = viewport.rect.width
        h = viewport.rect.height
        top_r, top_g, top_b, bot_r, bot_g, bot_b = gradient_endpoints
        img = LiteRGSS::Image.new(w, h)
        h.times do |y|
          t = y.to_f / [h - 1, 1].max
          r = (top_r * (1.0 - t) + bot_r * t).to_i
          g = (top_g * (1.0 - t) + bot_g * t).to_i
          b = (top_b * (1.0 - t) + bot_b * t).to_i
          img.fill_rect(0, y, w, 1, LiteRGSS::Color.new(r, g, b, 255))
        end
        # ShaderedSprite so we can optionally attach the nebula shader
        # later. With no shader bound it renders identically to a
        # plain Sprite (SFML samples the bitmap and blits it).
        @sprite = LiteRGSS::ShaderedSprite.new(viewport)
        # Sprite#bitmap= requires a LiteRGSS::Bitmap (GPU texture);
        # assigning an Image silently sets the sprite invisible.
        @sprite.bitmap = LiteRGSS::Bitmap.new(w, h)
        img.copy_to_bitmap(@sprite.bitmap)
        img.dispose
        @sprite.z = 0
        @w = w
        @h = h
      end

      # Attach the nebula shader to the existing gradient sprite. The
      # shader samples the sprite's bitmap (the pre-rendered gradient)
      # as its "base color" and adds the procedural nebula effect on
      # top — no separate sprite, no z-index ambiguity, no double-
      # painted gradient. Idempotent. Call after the splash has fully
      # come up on screen (see class comment for MIUI deferral
      # rationale).
      def enhance_with_nebula!(clock)
        return if @shader
        @shader = LiteRGSS::Shader.new(VERT_SRC, FRAG_SRC)
        @shader.set_float_uniform('u_resolution', [@w.to_f, @h.to_f])
        @shader.set_float_uniform('u_tint', [
          @icon_color[0] / 255.0,
          @icon_color[1] / 255.0,
          @icon_color[2] / 255.0,
        ])
        @shader.set_float_uniform('u_time', 0.0)
        @shader.set_float_uniform('u_nebula_intensity', 0.0)
        @sprite.shader = @shader
        @nebula_start_frame = clock.frame
      end

      def animate(clock, _state, _m)
        return unless @shader
        # u_nebula_intensity ramps 0 → 1 over NEBULA_FADE_IN_FRAMES,
        # so the shader output morphs smoothly from "pure gradient
        # sample" to "gradient + nebula" without a visible snap.
        age  = clock.frame - (@nebula_start_frame || 0)
        ramp = (age.to_f / NEBULA_FADE_IN_FRAMES).clamp(0.0, 1.0)
        @shader.set_float_uniform('u_nebula_intensity', Util.ease_out_cubic(ramp))
        @shader.set_float_uniform('u_time', clock.frame / Clock::FRAME_RATE.to_f)
      end

      def dispose
        return unless @sprite
        @sprite.bitmap.dispose if @sprite.bitmap && !@sprite.bitmap.disposed?
        @sprite.dispose unless @sprite.disposed?
        @sprite = nil
        @shader = nil
      end

      private

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

      # Explicit vertex shader. SFML's default vert doesn't declare
      # the varyings LiteCGSS's ShaderPreprocessor injects into every
      # fragment shader on GLES (v_texture_coordinates,
      # v_factor_npot, sf_color, …). Without matching varyings on the
      # vertex side, the GLES program silently fails to link.
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

      # Fragment shader: samples the sprite's bitmap (the pre-rendered
      # gradient) and adds the procedural nebula (flowing bands +
      # halo, icon-tinted) on top. u_nebula_intensity multiplies the
      # nebula contribution so the gradient is recovered exactly when
      # the uniform is 0.
      #
      # Do NOT declare `precision mediump float;` or `varying vec2
      # v_texture_coordinates;` here — LiteCGSS's ShaderPreprocessor
      # injects both. Redeclaring causes a GLSL "redefinition" error
      # on Adreno.
      FRAG_SRC = <<~FRAG
        uniform sampler2D texture;
        uniform float u_time;
        uniform float u_nebula_intensity;
        uniform vec3  u_tint;
        uniform vec2  u_resolution;

        void main() {
          // Base color = the static gradient that build() pre-rendered
          // into the sprite's bitmap. Tint by sf_color so the sprite's
          // opacity property (and any future tone effects) still apply.
          vec4 base = texture2D(texture, v_texture_coordinates) * sf_color;

          vec2 uv = v_texture_coordinates;
          vec2 aspect = vec2(u_resolution.x / max(u_resolution.y, 1.0), 1.0);
          vec2 p = (uv - 0.5) * aspect;

          float t = u_time * 0.08;

          // Two phase-modulated sine layers; the second is domain-
          // warped by the first to break out of straight-stripe
          // patterns. ~5 transcendentals/pixel — fits the mobile
          // budget without QueueBuffer pressure.
          float w1 = sin(p.x * 2.7 - t * 1.3 + sin(p.y * 1.4 + t * 0.6) * 1.7);
          float w2 = sin(p.y * 2.1 + t * 0.9 + cos(p.x * 1.6 - t * 0.5) * 1.2);
          float bands = smoothstep(0.10, 0.95, (w1 + w2 * 0.7) * 0.5 + 0.5);

          // Soft halo where the icon sits (slightly above middle) —
          // adds a "pool of light" under the icon without a bloom
          // pass.
          vec2  hc   = vec2(0.5, 0.42);
          float r    = length((uv - hc) * aspect);
          float halo = exp(-r * r * 6.0);

          // Slow hue shift between the icon tint and a swizzled
          // complement — chromatic motion that never leaves the
          // icon's color family.
          vec3 hue_b = mix(u_tint, u_tint.gbr, 0.6);
          vec3 hue   = mix(u_tint, hue_b, 0.5 + 0.5 * sin(t * 1.7 + w1));

          // Push the nebula color brighter than the gradient tint so
          // when blended at moderate strength it visibly ADDS light
          // instead of just tinting the gradient darker (which
          // standard alpha blending would otherwise do for any darker
          // source color).
          vec3 nebula_col = hue * 1.5;

          // Alpha = how strongly the nebula manifests at this pixel.
          // Vignette applied to alpha (not RGB) so the falloff is
          // "less nebula, more gradient" toward the edges instead of
          // a dark vignette ring.
          float vig = smoothstep(1.05, 0.40, length((uv - 0.5) * aspect));
          float intensity = (bands * 0.45 + halo * 0.55) * mix(0.4, 1.0, vig);
          intensity = clamp(intensity * u_nebula_intensity, 0.0, 0.85);

          // Standard alpha blend (in the shader, not the framebuffer)
          // — at intensity=0 result = base.rgb exactly, so disabling
          // the nebula (u_nebula_intensity=0) recovers the original
          // gradient pixel-perfect.
          vec3 col = mix(base.rgb, nebula_col, intensity);

          gl_FragColor = vec4(col, base.a);
        }
      FRAG
    end
  end
end
