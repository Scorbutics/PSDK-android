module Loader
  module Layers
    # Vertical gradient: icon-tinted dark at top → near-black at bottom.
    # The top color is peak-normalized so its brightest channel always
    # sits at GRADIENT_TOP_PEAK regardless of how dark or saturated the
    # icon is — keeps the gradient visible for very dark icons while
    # preserving the icon's hue. The bottom color uses the same hue
    # scaled to GRADIENT_BOT_PEAK so the gradient stays a single
    # chromatic family (no "purple-to-green" weirdness).
    class Background
      GRADIENT_TOP_PEAK = 56
      GRADIENT_BOT_PEAK = 11

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
        @sprite = LiteRGSS::Sprite.new(viewport)
        # Sprite#bitmap= requires a LiteRGSS::Bitmap (GPU texture);
        # assigning an Image silently sets the sprite invisible.
        @sprite.bitmap = LiteRGSS::Bitmap.new(w, h)
        img.copy_to_bitmap(@sprite.bitmap)
        img.dispose
        @sprite.z = 0
      end

      def animate(_clock, _state, _m)
        # static gradient — fade-out multiplier is intentionally
        # ignored so the gradient stays opaque even as the foreground
        # fades. Matches the original behavior (bg sprite was never in
        # the fade-out alpha calculation).
      end

      def dispose
        return unless @sprite
        @sprite.bitmap.dispose if @sprite.bitmap && !@sprite.bitmap.disposed?
        @sprite.dispose unless @sprite.disposed?
        @sprite = nil
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
    end
  end
end
