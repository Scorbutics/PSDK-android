unless defined?(Loader::Layers::Dust::BITMAP_SIZE)

module Loader
  module Layers
    # Stardust feedback layer — every touch spawns short-lived
    # sparkles at the contact point; release-on-icon triggers a radial
    # "bang" via #bang. One shared GPU texture (@bitmap) backs every
    # sprite, so spawning is cheap.
    class Dust
      BITMAP_SIZE    = 4
      LIFE_FRAMES    = 36   # 0.6s at 60Hz — long enough to read, short enough to feel snappy
      MAX            = 96   # hard ceiling — protects vs. finger-mashing while bitmap is shared
      TAP_COUNT      = 8    # particles per touch-began
      MOVE_COUNT     = 1    # particles per touch-moved frame (rate-limited to once per frame)
      RELEASE_COUNT  = 4    # particles per non-icon touch-ended
      BANG_COUNT     = 24   # radial burst on icon release
      BANG_SPEED_MIN = 2.5
      BANG_SPEED_MAX = 5.0

      def initialize
        @particles       = nil
        @bitmap          = nil
        @viewport        = nil
        @last_move_frame = -1
      end

      # Stardust bitmap shared by every sparkle sprite. Soft circular
      # falloff in bright cool-white (245, 250, 255) — same hue family
      # as the ambient twinkles (blue-biased white), but pushed close
      # to pure white for a brighter, more luminous core.
      def build(viewport)
        @viewport = viewport
        img = LiteRGSS::Image.new(BITMAP_SIZE, BITMAP_SIZE)
        half = (BITMAP_SIZE - 1) / 2.0
        BITMAP_SIZE.times do |y|
          BITMAP_SIZE.times do |x|
            dx = x - half
            dy = y - half
            d = Math.sqrt(dx * dx + dy * dy) / [half, 1.0].max
            next if d >= 1.0
            a = ((1.0 - d) ** 1.3 * 255).to_i.clamp(0, 255)
            img.set_pixel(x, y, LiteRGSS::Color.new(245, 250, 255, a))
          end
        end
        @bitmap = LiteRGSS::Bitmap.new(BITMAP_SIZE, BITMAP_SIZE)
        img.copy_to_bitmap(@bitmap)
        img.dispose
        @particles = []
      end

      # Spawn `count` short-lived sparkles centered at (lx, ly) in
      # logical canvas coords. Velocities are randomized in a small
      # cone; each particle is born with born_at so animate can age it
      # by frame delta rather than by wall-clock.
      def spawn(lx, ly, count, born_at:)
        return unless @particles && @bitmap && @viewport
        count.times do
          break if @particles.size >= MAX
          # Slight upward bias: feels like the dust is being lifted by
          # the finger rather than falling out of it.
          vx = (rand - 0.5) * 3.4
          vy = (rand - 0.5) * 3.0 - 0.6
          zoom = 0.9 + rand * 1.4
          @particles << make_particle(lx, ly, vx, vy, zoom, born_at)
        end
      end

      # spawn(), throttled to one spawn per frame regardless of touch
      # event rate (Android can deliver touch-moved faster than we
      # render).
      def spawn_throttled(lx, ly, count, frame)
        return if @last_move_frame == frame
        @last_move_frame = frame
        spawn(lx, ly, count, born_at: frame)
      end

      # Radial "bang": BANG_COUNT particles fired outward in evenly-
      # spaced directions with mild jitter. Used on release of the
      # icon — pairs with the shake to read as "logo just emitted
      # compile energy".
      def bang(cx, cy, born_at:)
        return unless @particles && @bitmap && @viewport
        BANG_COUNT.times do |i|
          break if @particles.size >= MAX
          angle = (i.to_f / BANG_COUNT) * 2.0 * Math::PI + (rand - 0.5) * 0.25
          speed = BANG_SPEED_MIN + rand * (BANG_SPEED_MAX - BANG_SPEED_MIN)
          vx = Math.cos(angle) * speed
          vy = Math.sin(angle) * speed
          zoom = 1.2 + rand * 0.8
          @particles << make_particle(cx, cy, vx, vy, zoom, born_at)
        end
      end

      def animate(clock, _state, m)
        return unless @particles
        @particles.reject! do |p|
          age = clock.frame - p[:born_at]
          if age >= LIFE_FRAMES
            p[:sprite].dispose unless p[:sprite].disposed?
            next true
          end
          s = p[:sprite]
          next true if s.disposed?
          t = age.to_f / LIFE_FRAMES
          eased = 1.0 - (1.0 - t) ** 3
          # Move + damp velocity so the particles settle rather than
          # fly off. dframe lets a single tick advance N "frames"
          # worth of motion after a GVL hold — rectangular-rule
          # integration; close enough at dust's short life.
          df = clock.dframe
          s.set_position((s.x + p[:vx] * df).to_i, (s.y + p[:vy] * df).to_i)
          # Exponentiated damping is exact for any df ≥ 0: an N-step
          # geometric decay collapses to one ** call.
          decay = 0.94 ** df
          p[:vx] *= decay
          # Gravity accumulation: pre-decay vy plus df steps of +0.06
          # each. Drift error is bounded by 0.06 * df² / 2, ~5 px
          # after a 500ms freeze (df=30), invisible at this size/life.
          p[:vy] = p[:vy] * decay + 0.06 * df
          s.opacity = ((1.0 - eased) * 255 * m).to_i.clamp(0, 255)
          # Zoom is hash-tracked (no Sprite#zoom getter in litergss2) —
          # write back to the sprite each frame from our authoritative
          # copy.
          p[:zoom] = (p[:zoom] * (0.96 ** df)).clamp(0.1, 4.0)
          s.zoom = p[:zoom]
          false
        end
      end

      def dispose
        # Dust sprites all reference the SHARED @bitmap. Dispose the
        # sprites first, then the bitmap exactly once — never via the
        # per-sprite "dispose its bitmap" path.
        if @particles
          @particles.each { |p| p[:sprite].dispose unless p[:sprite].disposed? }
          @particles = nil
        end
        @bitmap.dispose if @bitmap && !@bitmap.disposed?
        @bitmap = nil
      end

      private

      # Create one dust sprite and the bookkeeping hash that owns its
      # animated state. The hash carries position, velocity AND zoom
      # because LiteRGSS::Sprite exposes `zoom=` only — there's no
      # `zoom` reader, so we can't round-trip the current value
      # through the sprite each frame. Position has getters (`x`,
      # `y`), so it stays sprite-resident.
      def make_particle(lx, ly, vx, vy, zoom, born_at)
        s = LiteRGSS::Sprite.new(@viewport)
        s.bitmap = @bitmap
        s.set_origin(BITMAP_SIZE / 2, BITMAP_SIZE / 2)
        s.set_position(lx.to_i, ly.to_i)
        s.opacity = 255
        s.zoom = zoom
        s.z = 30
        { sprite: s, born_at: born_at, vx: vx, vy: vy, zoom: zoom }
      end
    end
  end
end

end # unless defined?(Loader::Layers::Dust::BITMAP_SIZE)
