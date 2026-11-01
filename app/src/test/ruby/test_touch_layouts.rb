# Unit tests for TouchLayouts — the gamepad's layout files: installed into a
# release, loaded into the overlay, the player's saved.
#
# Run:  ruby app/src/test/ruby/test_touch_layouts.rb

require 'minitest/autorun'
require 'tmpdir'
require File.expand_path('../../main/ruby/lib/touch_layouts', __dir__)

# embedded-ruby-vm's host channel, as far as TouchLayouts uses it
module VMHost
  class << self
    attr_accessor :emitted, :pending

    def emit(topic, _payload = '')
      emitted << topic
      true
    end

    def each_message(prefix)
      taken, self.pending = pending.partition { |topic, _| topic.start_with?(prefix) }
      taken.each { |topic, payload| yield topic, payload }
    end
  end
end

class TestTouchLayouts < Minitest::Test
  # The archive, as PhysFS shows it
  class FakeFs
    def initialize(files) = (@files = files)
    def exist?(path) = @files.key?(path)
    def read(path) = @files.fetch(path)
  end

  # What TouchLayouts uses of LiteRGSS::TouchOverlay
  class FakeOverlay
    attr_reader :loads, :clears
    attr_accessor :on_layout_saved, :on_settings

    def clear_layout
      @clears = clears.to_i + 1
    end

    def initialize = (@loads = [])

    # A JSON object is a valid layout file here
    def load_layout(json, read_image:, player: false)
      raise IOError, 'unreadable' if json == 'raise'

      @loads << [json, read_image, player]
      JSON.parse(json).is_a?(Hash)
    rescue JSON::ParserError
      false
    end
  end

  GAME = JSON.generate('version' => 1, 'buttons' => {
                         'run' => { 'key' => 'LShift', 'image' => 'touch/run.png' },
                         'evil' => { 'key' => 'RShift', 'image' => '../../escape.png' }
                       })

  # A window class whose update the game calls once per frame
  class FakeWindow
    def update = :updated
  end

  def setup
    @dir = Dir.mktmpdir
    VMHost.emitted = []
    VMHost.pending = []
  end

  def teardown
    FileUtils.rm_rf(@dir)
  end

  def test_the_game_file_and_its_images_go_into_the_release
    fs = FakeFs.new('Data/touch_layout.json' => GAME, 'Data/touch/run.png' => 'PNG', '../escape.png' => 'x')
    assert_equal 2, TouchLayouts.install_game_file(fs, @dir)
    assert_equal GAME, File.read(File.join(@dir, 'Data/touch_layout.json'))
    assert_equal 'PNG', File.read(File.join(@dir, 'Data/touch/run.png'))
    refute File.exist?(File.join(@dir, '../escape.png'))
  end

  def test_an_image_path_not_read_alike_everywhere_is_refused
    ['touch\\run.png', "touch/run\0.png", "t\xFF.png"].each do |path|
      refute TouchLayouts.safe?(path), path.inspect
    end
    assert TouchLayouts.safe?('touch/run.png')
  end

  def test_no_game_file_installs_nothing
    assert_equal 0, TouchLayouts.install_game_file(FakeFs.new({}), @dir)
  end

  def test_both_files_are_loaded_the_players_on_top
    fs = FakeFs.new('Data/touch_layout.json' => GAME, 'Data/touch/run.png' => 'game PNG')
    File.write(File.join(@dir, 'touch_layout.json'), '{"version": 1}')
    File.write(File.join(@dir, 'mine.png'), 'player PNG')
    overlay = FakeOverlay.new
    TouchLayouts.load(overlay, fs, @dir)
    (game_json, game_images, game_player), (player_json, player_images, player_player) = overlay.loads
    assert_equal [GAME, false, '{"version": 1}', true], [game_json, game_player, player_json, player_player]
    assert_equal 'game PNG', game_images.call('touch/run.png')
    assert_equal 'player PNG', player_images.call('mine.png')
    assert_raises(ArgumentError) { player_images.call('../secret') }
  end

  def test_the_editor_saves_the_players_file
    overlay = FakeOverlay.new
    TouchLayouts.load(overlay, FakeFs.new({}), @dir)
    assert_empty overlay.loads
    overlay.on_layout_saved.call('{"version": 1, "buttons": {}}')
    assert_equal '{"version": 1, "buttons": {}}', File.read(File.join(@dir, 'touch_layout.json'))
    assert_equal ['touch_layout.json'], Dir.children(@dir)
  end

  def test_a_broken_game_file_never_fails_the_compile
    ['[1]', 'null', '{"buttons": {"run": {"image": ""}}}', '{"buttons": {"run": {"image": "touch/"}}}'].each do |json|
      fs = FakeFs.new('Data/touch_layout.json' => json, 'Data/touch/' => nil)
      assert_equal 1, TouchLayouts.install_game_file(fs, @dir), json
    end
    fs = Class.new(FakeFs) { def read(_) = raise(IOError, 'openRead failed') }.new('Data/touch_layout.json' => '{}')
    _, err = capture_io { assert_equal 0, TouchLayouts.install_game_file(fs, @dir) }
    assert_match(/not installed/, err)
  end

  def test_a_broken_game_file_still_loads_the_players
    File.write(File.join(@dir, 'touch_layout.json'), '{"version": 1}')
    overlay = FakeOverlay.new
    capture_io { TouchLayouts.load(overlay, FakeFs.new('Data/touch_layout.json' => 'raise'), @dir) }
    assert_equal [true], overlay.loads.map(&:last)
    refute_nil overlay.on_layout_saved
  end

  def test_a_refused_player_file_is_kept_aside
    path = File.join(@dir, 'touch_layout.json')
    File.write(path, 'not json')
    overlay = FakeOverlay.new
    TouchLayouts.load(overlay, FakeFs.new({}), @dir)
    overlay.on_layout_saved.call('{"version": 1}')
    assert_equal 'not json', File.read("#{path}.broken")
    assert_equal '{"version": 1}', File.read(path)
  end

  def test_settings_tell_the_app_and_its_reload_message_loads_the_files_again
    File.write(File.join(@dir, 'touch_layout.json'), '{"version": 1}')
    overlay = FakeOverlay.new
    window_class = Class.new(FakeWindow)
    TouchLayouts.serve_settings(overlay, FakeFs.new({}), @dir, window_class)
    overlay.on_settings.call
    assert_equal ['overlay.settings'], VMHost.emitted
    window = window_class.new
    assert_equal :updated, window.update
    assert_empty overlay.loads
    VMHost.pending = [['overlay.reload', ''], ['overlay.other', '']]
    window.update
    assert_equal [1, [true]], [overlay.clears, overlay.loads.map(&:last)]
    assert_equal [['overlay.other', '']], VMHost.pending
  end

  def test_a_failing_reload_is_dropped
    overlay = FakeOverlay.new
    def overlay.clear_layout = raise(IOError, 'gone')
    window_class = Class.new(FakeWindow)
    TouchLayouts.serve_settings(overlay, FakeFs.new({}), @dir, window_class)
    VMHost.pending = [['overlay.reload', '']]
    _, err = capture_io { window_class.new.update }
    assert_match(/layout not reloaded: IOError/, err)
    assert_empty VMHost.pending
  end

  def test_a_runtime_without_settings_keeps_its_menu
    overlay = Struct.new(:loads).new([])
    window_class = Class.new(FakeWindow)
    TouchLayouts.serve_settings(overlay, FakeFs.new({}), @dir, window_class)
    refute window_class.ancestors.include?(TouchLayouts::Reload)
  end
end
