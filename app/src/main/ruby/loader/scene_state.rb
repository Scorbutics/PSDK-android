module Loader
  # Shared event/trigger state for the splash. Mutated by the touch
  # router and the external-status poller; read by every layer's
  # animate. Centralizing the trigger frame indexes here is what kills
  # the cross-layer spaghetti — layers never poke each other directly.
  class SceneState
    # Post-compile lift: shifts the splash centerpiece upward to make
    # room for the result card sliding up from the bottom. Staggered
    # per-element so the icon leads and the text follows.
    #
    # 100 px keeps the title at ~y=52 on a 240×489 logical canvas
    # (comfortable top breathing room) while clearing the success card.
    LIFT_OFFSET_PX       = 50
    LIFT_DURATION_FRAMES = 36   # 0.6s ease at 60Hz
    LIFT_DELAY_ICON      = 0
    LIFT_DELAY_TITLE     = 3
    LIFT_DELAY_VERSION   = 7
    LIFT_DELAY_STATUS    = 12

    # Unlift: settle the centerpiece back down to its resting position
    # once the user dismisses the success card. Same per-element delays
    # as the lift so the icon still leads (visually coherent: whichever
    # element led on the way up leads on the way down).
    UNLIFT_DURATION_FRAMES = LIFT_DURATION_FRAMES

    # Icon-tap ramp / spin-down. Shared by Layers::Icon (roll-in and
    # post-lift spin-down) and Layers::AmbientParticles (speed boost
    # ramp / fade). Same curve in both places so the warp-speed kickoff
    # reads as one coherent event rather than two independent triggers.
    ROLL_RAMP_FRAMES = 30
    SPIN_DOWN_FRAMES = 48

    attr_accessor :rolling_start, :lifted_start, :unlifted_start, :shake_start,
                  :pressed_finger,
                  :external_status_received, :first_frame_signaled

    def initialize
      @rolling_start            = nil
      @lifted_start             = nil
      @unlifted_start           = nil
      @shake_start              = nil
      @pressed_finger           = nil
      @external_status_received = false
      @first_frame_signaled     = false
    end

    # Lift offset in logical px for an element with the given start
    # delay (in frames). Returns 0 before the lift triggers and during
    # the per-element delay window, then eases from 0 to LIFT_OFFSET_PX
    # over LIFT_DURATION_FRAMES and holds at the full offset thereafter.
    # Y axis points down — callers subtract this value to move upward.
    #
    # Once @unlifted_start is set, the offset rides back down to 0 over
    # UNLIFT_DURATION_FRAMES (same per-element delay so the icon leads
    # the descent like it led the ascent).
    def lift_offset_for(delay_frames, current_frame)
      return 0.0 unless @lifted_start
      age = current_frame - @lifted_start - delay_frames
      return 0.0 if age <= 0
      t = [age.to_f / LIFT_DURATION_FRAMES, 1.0].min
      offset = LIFT_OFFSET_PX * Util.ease_out_cubic(t)

      return offset unless @unlifted_start
      unlift_age = current_frame - @unlifted_start - delay_frames
      return offset if unlift_age <= 0
      ut = [unlift_age.to_f / UNLIFT_DURATION_FRAMES, 1.0].min
      offset * (1.0 - Util.ease_out_cubic(ut))
    end
  end
end
