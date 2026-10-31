# Unit tests for Loader::HostMessages — how the splash applies the messages
# the host sends (LoaderMessages.kt) through VMHost.
#
# VMHost is the runtime's (embedded-ruby-vm); this stand-in keeps its
# each_message contract: the pending messages under a prefix, oldest first,
# taken once.
#
# Run:  ruby app/src/test/ruby/test_loader_host_messages.rb

require 'minitest/autorun'

module VMHost
  @pending = []

  class << self
    def deliver(topic, payload = '') = @pending << [topic, payload]
    def reset! = @pending.clear

    def each_message(prefix)
      taken, @pending = @pending.partition { |topic, _| topic.start_with?(prefix) }
      taken.each { |topic, payload| yield topic, payload }
      taken.size
    end
  end
end

def loader_say(message) = raise(message)

FakeState = Struct.new(:rolling_start, :lifted_start, :unlifted_start, :shake_start, :pressed_finger,
                       :external_status_received)

class FakeText
  attr_reader :statuses

  def initialize = (@statuses = [])
  def update_status(text) = @statuses << text
end

class FakeIcon
  attr_reader :resets

  def initialize = (@resets = 0)
  def reset_visual! = @resets += 1
  def center_x = 10
  def center_y = 20
end

class FakeDust
  attr_reader :bangs

  def initialize = (@bangs = [])
  def bang(x, y, born_at:) = @bangs << [x, y, born_at]
end

FakeClock = Struct.new(:frame)

require File.expand_path('../../main/ruby/loader/host_messages', __dir__)

class TestLoaderHostMessages < Minitest::Test
  def setup
    VMHost.reset!
    @icon = FakeIcon.new
    @dust = FakeDust.new
    @clock = FakeClock.new(100)
    Loader::HostMessages.last = nil
    @messages = new_splash
  end

  # The scene of a splash restarted in the same VM
  def new_splash
    @state = FakeState.new
    @text = FakeText.new
    Loader::HostMessages.new(state: @state, text_layer: @text, dust: @dust, icon: @icon, clock: @clock)
  end

  def test_messages_apply_in_order
    VMHost.deliver('loader.status', 'Check engine...')
    VMHost.deliver('loader.lift', 'Compilation done')
    @clock.frame = 120
    @messages.poll

    assert_equal 120, @state.lifted_start
    assert_equal ['Check engine...', 'Compilation done'], @text.statuses
  end

  def test_a_repeated_status_is_shown_once
    @messages.poll
    VMHost.deliver('loader.status', 'Finalizing...')
    VMHost.deliver('loader.status', 'Finalizing...')
    @messages.poll

    assert_equal ['Finalizing...'], @text.statuses
  end

  def test_reset_wipes_the_compile_cycle_then_shows_its_text
    @messages.poll
    @state.lifted_start = @state.unlifted_start = @state.rolling_start = @state.shake_start = 5
    @state.pressed_finger = 1
    VMHost.deliver('loader.reset', '< Tap logo to play >')
    @messages.poll

    assert_equal [nil] * 5, @state.to_a[0, 5]
    assert_equal 1, @icon.resets
    assert_equal ['< Tap logo to play >'], @text.statuses
  end

  def test_tap_plays_the_release_on_icon_visual
    @messages.poll
    VMHost.deliver('loader.tap')
    @messages.poll

    assert_equal 100, @state.rolling_start
    assert_equal 100, @state.shake_start
    assert_equal [[10, 20, 100]], @dust.bangs
  end

  def test_unlift_settles_the_centerpiece
    @messages.poll
    VMHost.deliver('loader.unlift')
    @messages.poll

    assert_equal 100, @state.unlifted_start
  end

  def test_a_restarted_splash_shows_what_the_previous_one_showed
    VMHost.deliver('loader.lift', 'Compilation done')
    @messages.poll

    @clock.frame = 300
    new_splash.poll
    assert_equal 300, @state.lifted_start
    assert_equal ['Compilation done'], @text.statuses
  end

  def test_a_message_the_dying_splash_took_reaches_the_next_one
    @messages.poll
    VMHost.deliver('loader.status', 'Finalizing...')
    @messages.poll

    new_splash.poll
    assert_equal ['Finalizing...'], @text.statuses
  end

  def test_clear_drops_what_came_before_it
    VMHost.deliver('loader.lift', 'Compilation done')
    VMHost.deliver('loader.clear')
    VMHost.deliver('loader.status', '< Tap logo to play >')
    @messages.poll

    assert_nil @state.lifted_start
    assert_equal ['< Tap logo to play >'], @text.statuses
  end

  def test_a_new_splash_taking_the_clear_does_not_restore_the_old_state
    VMHost.deliver('loader.lift', 'Compilation done')
    @messages.poll
    VMHost.deliver('loader.clear')
    VMHost.deliver('loader.status', '< Tap logo to compile >')

    new_splash.poll
    assert_nil @state.lifted_start
    assert_equal ['< Tap logo to compile >'], @text.statuses
  end

  def test_after_a_clear_a_new_splash_shows_nothing_again
    VMHost.deliver('loader.lift', 'Compilation done')
    @messages.poll
    VMHost.deliver('loader.clear')
    @messages.poll

    new_splash.poll
    assert_nil @state.lifted_start
    assert_empty @text.statuses
  end

  def test_messages_of_other_consumers_are_left_alone
    VMHost.deliver('overlay.reload', '{}')
    @messages.poll

    assert_equal 1, VMHost.each_message('overlay.') { |_, _| }
  end
end
