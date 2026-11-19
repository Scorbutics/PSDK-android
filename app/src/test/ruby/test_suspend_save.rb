# Unit tests for SuspendSave: the suspend file written in the background,
# from the game or its last safe moment, deleted back in the foreground, and
# the resume, against a fake PSDK (its save API, the RMXP globals) and a fake
# LiteRGSS::HostLifecycle.
#
# Run:  ruby app/src/test/ruby/test_suspend_save.rb

require 'minitest/autorun'
require 'tmpdir'

module LiteRGSS
  class DisplayWindow
    def update; end
  end
end

# The lifecycle hooks: the test calls them as the window's focus events would
module FakeLifecycle
  class << self
    attr_reader :background, :foreground

    def install; end

    def on_background(&block)
      @background = block
    end

    def on_foreground(&block)
      @foreground = block
    end
  end
end

class Scene_Map; end

module GamePlay
  class Base
    attr_reader :running

    def initialize
      @running = true
    end
  end

  # PSDK's save API: a save is the game's state, as text here
  class Save
    class << self
      attr_accessor :state, :loaded

      def save(_filename, no_file)
        raise 'a suspend save never writes a file of the player' unless no_file

        $game_system.save_count += 1
        "save:#{state}"
      end

      def load(filename, no_load_parameter:)
        raise 'load_parameters is load_game\'s' unless no_load_parameter

        data = File.binread(filename)
        data.start_with?('save:') ? data.delete_prefix('save:') : nil
      end
    end
  end

  # PSDK's Load: load_game enters the game held by @all_saves[@index]
  class Load < Base
    def load_game
      Save.loaded = @all_saves[@index]
      $scene = Scene_Map.new
    end
    private :load_game
  end
end

Interpreter = Struct.new(:running) do
  def running?
    running
  end
end
GameSystem = Struct.new(:save_count, :map_interpreter, :save_disabled)
GameTemp = Struct.new(:message_window_showing, :player_transferring)

require_relative '../../main/ruby/lib/suspend_save'

class TestSuspendSave < Minitest::Test
  def setup
    @dir = Dir.mktmpdir
    @path = File.join(@dir, 'game.suspend')
    @now = 0
    $scene = Scene_Map.new
    $game_system = GameSystem.new(0, Interpreter.new(false), false)
    $game_temp = GameTemp.new(false, false)
    $game_player = Object.new
    GamePlay::Save.state = 'on the map'
    GamePlay::Save.loaded = nil
  end

  def teardown
    SuspendSave.enabled = true
    FileUtils.remove_entry(@dir)
  end

  def install(resume: false)
    SuspendSave.install(path: @path, resume: resume, lifecycle: FakeLifecycle, clock: -> { @now })
  end

  def suspended
    File.exist?(@path) ? File.binread(@path) : nil
  end

  def test_the_game_is_written_in_the_background_at_a_safe_moment
    install
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
    assert_equal 0, $game_system.save_count, 'not counted as a save of the player'
  end

  def test_back_in_the_foreground_the_file_goes
    install
    FakeLifecycle.background.call
    FakeLifecycle.foreground.call
    assert_nil suspended
  end

  def test_in_a_battle_the_last_safe_moment_is_written
    install
    SuspendSave.tick # on the map: snapshot
    $scene = GamePlay::Base.new # the battle
    GamePlay::Save.state = 'mid-battle'
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
  end

  def test_an_event_running_or_a_message_is_not_a_safe_moment
    install
    SuspendSave.tick
    GamePlay::Save.state = 'cutscene'
    $game_system.map_interpreter.running = true
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
    $game_system.map_interpreter.running = false
    $game_temp.message_window_showing = true
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
  end

  def test_nothing_is_written_before_any_safe_moment
    $scene = GamePlay::Base.new # the title
    install
    FakeLifecycle.background.call
    assert_nil suspended
  end

  def test_snapshots_are_refreshed_by_time_and_after_a_save_of_the_player
    install
    SuspendSave.tick
    GamePlay::Save.state = 'later'
    @now = 10
    SuspendSave.tick
    $scene = GamePlay::Base.new
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended, 'not refreshed before the interval'

    $scene = Scene_Map.new
    $game_system.save_count += 1 # the player saved
    SuspendSave.tick
    $scene = GamePlay::Base.new
    FakeLifecycle.background.call
    assert_equal 'save:later', suspended

    GamePlay::Save.state = 'much later'
    $scene = Scene_Map.new
    @now = 10 + SuspendSave::SNAPSHOT_INTERVAL
    SuspendSave.tick
    $scene = GamePlay::Base.new
    FakeLifecycle.background.call
    assert_equal 'save:much later', suspended
  end

  def test_the_resume_enters_the_suspended_game_at_the_first_scene
    File.binwrite(@path, 'save:where I was')
    $scene = nil # booting
    install(resume: true)
    SuspendSave.tick
    assert_nil GamePlay::Save.loaded, 'no scene yet'
    title = GamePlay::Base.new
    $scene = title
    SuspendSave.tick
    assert_equal 'where I was', GamePlay::Save.loaded
    assert_instance_of Scene_Map, $scene
    refute title.running, 'the title stops'
    assert_nil suspended, 'used once'
  end

  def test_not_resumed_an_old_file_goes
    File.binwrite(@path, 'save:an older game')
    install(resume: false)
    assert_nil suspended
  end

  def test_an_unreadable_file_lets_the_game_start_as_usual
    File.binwrite(@path, 'garbage')
    install(resume: true)
    title = GamePlay::Base.new
    $scene = title
    SuspendSave.tick
    assert_same title, $scene
    assert_nil GamePlay::Save.loaded
  end

  def test_where_saving_is_disabled_the_last_moment_before_is_written
    install
    SuspendSave.tick
    GamePlay::Save.state = 'in the no-save part'
    $game_system.save_disabled = true # Change Save Access: disabled
    @now = 10 * SuspendSave::SNAPSHOT_INTERVAL
    SuspendSave.tick
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
  end

  def test_a_game_script_turns_it_off
    install
    SuspendSave.tick
    SuspendSave.enabled = false
    FakeLifecycle.background.call
    assert_nil suspended
    SuspendSave.enabled = true
    FakeLifecycle.background.call
    assert_equal 'save:on the map', suspended
  end

  def test_no_file_no_suspend_save
    refute SuspendSave.install(path: nil, resume: false, lifecycle: FakeLifecycle)
  end
end
