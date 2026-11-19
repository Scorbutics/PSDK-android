# Guarded against re-eval on every loader workload swap — see util.rb
# for the rationale; the rest of the loader preludes follow the same
# `unless defined?(<first-constant-in-this-file>)` pattern.
unless defined?(Loader::Clock::FRAME_RATE)

module Loader
  # Wall-clock-driven animation clock.
  #
  # Derives @frame and @dframe from the monotonic clock anchored at
  # start!. @dframe is the integer number of frames elapsed since the
  # previous tick — integrators (positions, angles, velocity decay)
  # scale by it so the animation "catches up" to wall time after a GVL
  # hold. Phase-based animations (sin/cos, eased reveals) automatically
  # track wall time since they're keyed off frame absolute.
  #
  # Frames are drawn at DRAW_RATE (#pace!), not as fast as the display
  # goes (vsync at 120 Hz on some phones). The splash runs next to the
  # script compilation, in the same VM: frames it doesn't draw are GVL
  # time (and battery) left to it. FRAME_RATE stays the unit animations
  # are written in; the frames counted are wall-clock ones.
  class Clock
    FRAME_RATE   = 60
    DRAW_RATE    = 30
    OUTRO_FRAMES = 60

    attr_reader :frame, :dframe, :fade_out_frames

    # @param now [#call] monotonic seconds
    # @param sleeper [#call] sleeps the given seconds (letting the GVL go)
    def initialize(now: -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) }, sleeper: ->(seconds) { sleep(seconds) })
      @now = now
      @sleeper = sleeper
      @frame = 0
      @dframe = 0
      @wall_start_at = nil
      @fade_out_start = nil
      @fade_out_frames = OUTRO_FRAMES
      @next_draw_at = nil
    end

    # Waits until the next frame is due at DRAW_RATE. A late frame doesn't
    # make the next ones hurry: the schedule restarts from it.
    def pace!
      now = @now.call
      return @next_draw_at = now unless @next_draw_at

      @next_draw_at += 1.0 / DRAW_RATE
      delay = @next_draw_at - now
      if delay > 0
        @sleeper.call(delay)
      else
        @next_draw_at = now
      end
    end

    # Anchor wall-clock. Call AFTER heavy first-frame work (gradient gen,
    # glow falloff) — starting here means intros reveal at the expected
    # real-world pace regardless of cold-start cost.
    def start!
      @wall_start_at = @now.call
    end

    def tick!
      now = @now.call
      prev_frame = @frame
      @frame = ((now - @wall_start_at) * FRAME_RATE).to_i
      # max() guards against the rare case where the monotonic clock
      # didn't advance enough to bump the integer floor between two
      # ticks (fast vsync, ratio rounding). dframe < 0 is impossible by
      # CLOCK_MONOTONIC contract, but defensive anyway.
      @dframe = [@frame - prev_frame, 0].max
    end

    def request_fade_out
      @fade_out_start ||= @frame
    end

    def fade_out_multiplier
      return 1.0 unless @fade_out_start
      progress = (@frame - @fade_out_start).to_f / @fade_out_frames
      m = 1.0 - Util.ease_out_cubic(progress.clamp(0.0, 1.0))
      [m, 0.0].max
    end
  end
end

end # unless defined?(Loader::Clock::FRAME_RATE)
