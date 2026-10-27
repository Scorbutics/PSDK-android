unless defined?(Loader::Layers::AmbientParticles::COUNT)

module Loader
  module Layers
    # Tiny drifting "stars" — gives the screen low-frequency motion.
    #
    # Pre-tap: 0.15..0.31 px/frame slow drift (idle / "waiting for you"
    # feel). Post-tap: scaled by SPEED_BOOST — paired with the rolling
    # icon and the bang, it reads as "warp-speed, we're working now".
    # The transition eases in over ROLL_RAMP_FRAMES on the same curve
    # as the icon spin so both effects feel like one coherent kickoff.
    class AmbientParticles
      COUNT       = 9
      SPEED_BOOST = 50.0

      def initialize
        @particles = nil
        @viewport  = nil
      end

      def build(viewport)
        @viewport = viewport
        src = LiteRGSS::Image.new(2, 2)
        src.fill_rect(0, 0, 2, 2, LiteRGSS::Color.new(220, 230, 255, 255))
        w = viewport.rect.width
        h = viewport.rect.height
        @particles = COUNT.times.map do |i|
          bmp = LiteRGSS::Bitmap.new(2, 2)
          src.copy_to_bitmap(bmp)
          s = LiteRGSS::Sprite.new(viewport)
          s.bitmap = bmp
          s.z = 1
          s.set_position(((i + 1) * 37 + 11) % w, ((i * 53) + 19) % h)
          s.opacity = 40 + ((i * 23) % 60)
          s.zoom = 0.5 + ((i % 3) * 0.4)
          s
        end
        src.dispose
      end

      def animate(clock, state, m)
        return unless @particles
        w = @viewport.rect.width
        h = @viewport.rect.height
        boost = speed_multiplier(clock, state)
        @particles.each_with_index do |s, i|
          next if s.disposed?
          # Per-frame drift scaled by dframe — after a GVL hold the
          # stars land at the wall-time-correct y, modulo-wrapped
          # exactly as if we'd been ticking continuously. Twinkle
          # phase is sine-of-frame, so it tracks automatically.
          speed = (0.15 + (i % 3) * 0.08) * boost
          y = (s.y - speed * clock.dframe) % (h + 4)
          y = h + 4 if y < -2
          s.y = y
          twinkle = (Math.sin(clock.frame * 0.05 + i * 1.3) + 1.0) / 2.0
          s.opacity = ((40 + 90 * twinkle) * m).to_i
        end
      end

      def dispose
        return unless @particles
        @particles.each do |s|
          s.bitmap.dispose if s.bitmap && !s.bitmap.disposed?
          s.dispose unless s.disposed?
        end
        @particles = nil
      end

      private

      # 1.0 before the user taps the icon, ramps up to SPEED_BOOST over
      # SceneState::ROLL_RAMP_FRAMES once rolling_start fires, then
      # fades back to 1× over SceneState::SPIN_DOWN_FRAMES once
      # lifted_start fires (compile finished).
      def speed_multiplier(clock, state)
        return 1.0 unless state.rolling_start
        age  = clock.frame - state.rolling_start
        ramp = Util.ease_out_cubic([age.to_f / SceneState::ROLL_RAMP_FRAMES, 1.0].min)
        boost = 1.0 + (SPEED_BOOST - 1.0) * ramp

        if state.lifted_start
          lift_age = clock.frame - state.lifted_start
          fade = Util.ease_out_cubic([lift_age.to_f / SceneState::SPIN_DOWN_FRAMES, 1.0].min)
          boost = boost + (1.0 - boost) * fade
        end

        boost
      end
    end
  end
end

end # unless defined?(Loader::Layers::AmbientParticles::COUNT)
