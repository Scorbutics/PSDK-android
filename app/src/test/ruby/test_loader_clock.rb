# Unit tests for Loader::Clock — the splash's wall-clock frames and its
# frame pacing (#pace!), on a fake clock.
#
# Run:  ruby app/src/test/ruby/test_loader_clock.rb

require 'minitest/autorun'

module Loader
  module Util
    def self.ease_out_cubic(t) = 1 - (1 - t)**3
  end
end

require_relative '../../main/ruby/loader/clock'

class TestLoaderClock < Minitest::Test
  PERIOD = 1.0 / Loader::Clock::DRAW_RATE

  def setup
    @time = 100.0
    @slept = []
    @clock = Loader::Clock.new(now: -> { @time }, sleeper: ->(seconds) { @slept << seconds; @time += seconds })
  end

  def test_a_fast_frame_waits_for_the_rest_of_its_period
    @clock.pace! # first frame: schedule starts
    @time += 0.010 # drawn in 10 ms
    @clock.pace!
    assert_equal 1, @slept.size
    assert_in_delta PERIOD - 0.010, @slept.last, 1e-9
  end

  def test_frames_keep_the_draw_rate
    @clock.pace!
    30.times do
      @time += 0.005
      @clock.pace!
    end
    assert_in_delta 100.0 + 30 * PERIOD, @time, 1e-9
  end

  def test_a_late_frame_does_not_make_the_next_ones_hurry
    @clock.pace!
    @time += 0.5 # one frame took half a second (the compile held the GVL)
    @clock.pace!
    assert_empty @slept
    @time += 0.005
    @clock.pace!
    assert_in_delta PERIOD - 0.005, @slept.last, 1e-9
  end

  def test_frames_count_wall_time_at_the_animation_rate
    @clock.start!
    @time += 1.0
    @clock.tick!
    assert_equal Loader::Clock::FRAME_RATE, @clock.frame
    @time += 0.5
    @clock.tick!
    assert_equal Loader::Clock::FRAME_RATE / 2, @clock.dframe
  end
end
