unless defined?(Loader::Layers::Icon::GLOW_SIZE)

module Loader
  module Layers
    # Centerpiece icon + soft halo glow.
    #
    # Composite state machine driven by SceneState:
    #   - intro fade-in over 60 frames (pure frame-based ease)
    #   - breathing zoom (low-amplitude sin)
    #   - press depression while state.pressed_finger is set
    #   - shake on state.shake_start (decaying high-frequency sine)
    #   - rolling spin from state.rolling_start (ramps to steady rate)
    #   - lift offset on state.lifted_start (drains resting y upward)
    #   - spin-down post-lift (decel then ease angle back to 0)
    #
    # Owns the touch hit rect; it tracks the visual y as the icon
    # lifts so taps on the lifted logo still register.
    class Icon
      # Halo size bumped from 96 → 144 when the splash became the
      # activity's main content (compile wizard archive card is hidden
      # once the archive is valid). Bigger halo reads as "this is the
      # centerpiece" — also gives the tap-target visual weight
      # commensurate with its new role.
      GLOW_SIZE = 144

      # Peak luminance targets for icon-tinted accents. Same idea as
      # Background's gradient peaks — peak-normalize the median icon
      # color so accents read at the intended brightness regardless of
      # how dark the icon is.
      HALO_PEAK = 220
      CTA_PEAK  = 215

      # Extra logical-px halo around the icon bitmap, so the tap
      # target is forgiving — the icon is ~64px but a fingertip is
      # much wider.
      HIT_PADDING = 24

      # Logical size of the icon's longest side. The source can be PSDK's
      # 32x32 game icon or the larger project.studio icon (154x154 for
      # some projects); both are shown at the same size.
      DISPLAY_SIZE = 32

      # Press feedback: icon zoom is offset by PRESS_DEPRESSION when
      # fully pressed, eased toward target with PRESS_SPRING per frame
      # (critically damped feel — no overshoot). Breathing is
      # suppressed proportionally so a pressed icon really does feel
      # "frozen and smaller".
      PRESS_DEPRESSION = 0.12
      PRESS_SPRING     = 0.35

      # Release shake — a decaying high-frequency sine on both axes.
      # SHAKE_FRAMES caps the duration; SHAKE_AMPLITUDE is the peak
      # displacement on frame 0.
      SHAKE_FRAMES    = 22
      SHAKE_AMPLITUDE = 7

      # Post-tap "rolling" rotation. ROLL_SPEED_DEG_PER_FRAME is the
      # steady-state rate; the icon eases into it over
      # SceneState::ROLL_RAMP_FRAMES. ROLL_DIRECTION is the sign of
      # the angular step — counter-clockwise pairs better with the
      # upward bias of the dust bang. 25 deg/frame * 60Hz = 1500
      # deg/sec, ~one full turn every 0.24s — visibly fast, matches
      # the warp-speed feel of AmbientParticles' speed boost without
      # becoming a blur.
      ROLL_SPEED_DEG_PER_FRAME = 25.0
      ROLL_DIRECTION           = -1

      attr_reader :center_x, :center_y, :icon_w, :icon_h

      def initialize(icon_color, icon_bitmap, halo: true)
        @icon_color   = icon_color
        @icon_bitmap  = icon_bitmap
        @halo_enabled = halo
        @press_factor = 0.0
        # Authoritative angle (in degrees) written back to the sprite
        # each frame. Tracked here rather than read from the sprite so
        # the ramp math is monotonic even across SFML quantization.
        @icon_angle   = 0.0
        # Placeholder until build() learns the real bitmap size.
        @icon_w, @icon_h = 64, 64
      end

      def build(viewport, window_width, window_height)
        @center_x = window_width / 2
        @center_y = window_height / 2

        build_halo(viewport) if @halo_enabled

        return unless @icon_bitmap

        bitmap_w = @icon_bitmap.width
        bitmap_h = @icon_bitmap.height
        @icon_scale = DISPLAY_SIZE.to_f / [bitmap_w, bitmap_h, 1].max
        @icon_w = (bitmap_w * @icon_scale).round
        @icon_h = (bitmap_h * @icon_scale).round

        @icon = LiteRGSS::Sprite.new(viewport)
        @icon.bitmap = @icon_bitmap
        @icon.set_origin(bitmap_w / 2, bitmap_h / 2)
        @icon.set_position(@center_x, @center_y)
        @icon.opacity = 0
        @icon.zoom = 0.4 * @icon_scale
        @icon.z = 10

        # Hit rect anchored at the icon's steady-state size (zoom=1.0)
        # rather than the intro starting zoom of 0.4, so users who tap
        # during the intro fade-in still land on the target. The actual
        # sprite breathes within ±0.03 zoom; the padding swallows that.
        @hit_rect = [
          @center_x - @icon_w / 2 - HIT_PADDING,
          @center_y - @icon_h / 2 - HIT_PADDING,
          @icon_w + 2 * HIT_PADDING,
          @icon_h + 2 * HIT_PADDING,
        ]
      end

      # Soft additive-looking radial halo. Tinted with @icon_color so
      # it shares a hue family with the gradient. In default/hub state
      # (no icon bitmap) it sits behind the Kotlin "+" FAB the hub
      # centres over the GL surface; skipped while preparing (nothing
      # sits at the centre then).
      def build_halo(viewport)
        gc = halo_color
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
            g.set_pixel(x, y, LiteRGSS::Color.new(gc[0], gc[1], gc[2], a))
          end
        end
        @glow = LiteRGSS::Sprite.new(viewport)
        @glow.bitmap = LiteRGSS::Bitmap.new(GLOW_SIZE, GLOW_SIZE)
        g.copy_to_bitmap(@glow.bitmap)
        g.dispose
        @glow.set_origin(GLOW_SIZE / 2, GLOW_SIZE / 2)
        @glow.set_position(@center_x, @center_y)
        @glow.opacity = 0
        @glow.z = 5
      end

      def animate(clock, state, m)
        intro = Util.ease_out_cubic([clock.frame / 60.0, 1.0].min)
        base_zoom = 0.4 + 0.6 * intro
        breath = Math.sin(clock.frame * 0.09) * 0.03 * intro

        # Press: ease @press_factor toward 1.0 (pressed) or 0.0
        # (released). The depression reduces zoom; breathing is scaled
        # by (1 - factor) so a fully pressed icon really does freeze.
        target_press   = state.pressed_finger ? 1.0 : 0.0
        @press_factor += (target_press - @press_factor) * PRESS_SPRING
        press_z        = -PRESS_DEPRESSION * @press_factor
        breath        *= (1.0 - @press_factor)

        # Decaying release shake. SHAKE_FRAMES sets the window; once
        # past it the offset goes to 0 and shake_start is cleared so we
        # don't burn cycles on dead state.
        shake_x = 0.0
        shake_y = 0.0
        if state.shake_start
          sf = clock.frame - state.shake_start
          if sf < SHAKE_FRAMES
            decay = 1.0 - sf.to_f / SHAKE_FRAMES
            shake_x = Math.sin(sf * 1.8) * SHAKE_AMPLITUDE * decay
            shake_y = Math.sin(sf * 2.3 + 0.7) * SHAKE_AMPLITUDE * 0.6 * decay
          else
            state.shake_start = nil
          end
        end

        # Lift drains away the centerpiece's resting y to make room for
        # the result card. The glow follows the icon exactly (same
        # delay), so the halo never visually detaches from the logo.
        icon_lift = state.lift_offset_for(SceneState::LIFT_DELAY_ICON, clock.frame)
        icon_x = (@center_x + shake_x).to_i
        icon_y = (@center_y + shake_y - icon_lift).to_i

        # Keep the tap hitbox tracking the visual y so a finger landing
        # on the lifted logo still registers. We only follow the lift
        # offset, not the shake — shake is brief (~22 frames) and the
        # HIT_PADDING halo already absorbs ±SHAKE_AMPLITUDE px. y-axis
        # only: there's no horizontal motion.
        if @hit_rect
          @hit_rect[1] = (@center_y - icon_lift - @icon_h / 2 - HIT_PADDING).to_i
        end

        # Rolling spin: ramps from 0 to ROLL_SPEED_DEG_PER_FRAME over
        # ROLL_RAMP_FRAMES, then runs at steady state. Glow is
        # rotationally symmetric so only the icon sprite is rotated.
        # Scaled by dframe so the spin tracks wall time across a GVL
        # hold.
        #
        # Once lifted_start fires (= compile finished), we transition
        # into a two-phase spin-down:
        #   Phase A (0..SPIN_DOWN_FRAMES): keep integrating spin,
        #     scaled by a decay curve from 1 → 0. Icon visibly slows.
        #   Phase B (post-SPIN_DOWN_FRAMES): pull @icon_angle toward 0
        #     on the shortest arc with exponential decay. Snap to 0
        #     when within 0.5°.
        if state.rolling_start
          if state.lifted_start
            spin_down_age = clock.frame - state.lifted_start
            if spin_down_age < SceneState::SPIN_DOWN_FRAMES
              decay = 1.0 - Util.ease_out_cubic(spin_down_age.to_f / SceneState::SPIN_DOWN_FRAMES)
              step  = ROLL_SPEED_DEG_PER_FRAME * ROLL_DIRECTION * decay * clock.dframe
              @icon_angle = (@icon_angle + step) % 360.0
            else
              # Shortest-arc ease toward 0. Geometric per-frame pull
              # (15%) exponentiated by dframe so a GVL hold doesn't
              # overshoot.
              delta = -@icon_angle
              delta += 360.0 if delta < -180.0
              delta -= 360.0 if delta >  180.0
              if delta.abs < 0.5
                @icon_angle = 0.0
              else
                @icon_angle += delta * (1.0 - 0.85 ** clock.dframe)
                @icon_angle %= 360.0
                @icon_angle += 360.0 if @icon_angle < 0.0
              end
            end
          else
            age  = clock.frame - state.rolling_start
            ramp = Util.ease_out_cubic([age.to_f / SceneState::ROLL_RAMP_FRAMES, 1.0].min)
            step = ROLL_SPEED_DEG_PER_FRAME * ROLL_DIRECTION * ramp * clock.dframe
            @icon_angle = (@icon_angle + step) % 360.0
          end
        end

        if @icon && !@icon.disposed?
          @icon.zoom = (base_zoom + breath + press_z) * @icon_scale
          @icon.opacity = (255 * intro * m).to_i
          @icon.set_position(icon_x, icon_y)
          @icon.angle = @icon_angle if state.rolling_start
        end
        if @glow && !@glow.disposed?
          halo_pulse = 0.7 + 0.3 * Math.sin(clock.frame * 0.07)
          @glow.zoom = (base_zoom + press_z) * 1.05 + breath * 0.5
          @glow.opacity = (170 * intro * halo_pulse * m).to_i
          @glow.set_position(icon_x, icon_y)
        end
      end

      def hit?(lx, ly)
        return false unless @hit_rect
        rx, ry, rw, rh = @hit_rect
        lx >= rx && lx < rx + rw && ly >= ry && ly < ry + rh
      end

      # Drop any lingering rotation. After a compile cycle the icon's
      # spin-down may not have reached 0 yet (especially if the user
      # dismissed the result card before SPIN_DOWN_FRAMES elapsed); and
      # because animate's `@icon.angle = @icon_angle if state.rolling_start`
      # write is gated on state.rolling_start, nulling rolling_start alone
      # would freeze the sprite at its last non-zero angle. Reset both the
      # tracked angle AND push it to the sprite so the next frame draws
      # axis-aligned regardless of what the spin-down was mid-easing.
      def reset_visual!
        @icon_angle = 0.0
        @icon.angle = 0.0 if @icon && !@icon.disposed?
      end

      def present?
        !@icon.nil?
      end

      # CTA accent: peak-normalize toward CTA_PEAK — the CTA text reads
      # cleaner at ~200 than at full halo brightness, and the outline
      # shadow gives it enough pop on top of the gradient. Exposed so
      # the Text layer can pick up the same accent.
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

      def dispose
        [@icon, @glow].compact.each do |s|
          b = s.respond_to?(:bitmap) ? s.bitmap : nil
          b.dispose if b && !b.disposed?
          s.dispose unless s.disposed?
        end
        @icon = nil
        @glow = nil
      end

      private

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
    end
  end
end

end # unless defined?(Loader::Layers::Icon::GLOW_SIZE)
