# Unit tests for PsdkGameArea: PSDK's viewport offsets moved to the window's
# game area, against a fake LiteRGSS (DisplayWindow#game_area= is LiteRGSS's).
#
# Run:  ruby app/src/test/ruby/test_game_area.rb

require 'minitest/autorun'

module LiteRGSS
  class DisplayWindow
    attr_reader :game_area

    def initialize(width, height)
      @width = width
      @height = height
    end

    def game_area=(area)
      raise LiteRGSS::Error, 'Disposed Object' if @disposed

      @game_area = area
    end

    def width
      @game_area ? @game_area[2] : @width
    end

    def height
      @game_area ? @game_area[3] : @height
    end

    def screen_width
      @width
    end

    def screen_height
      @height
    end

    def dispose!
      @disposed = true
    end
  end

  class Error < StandardError; end
end

# PSDK's ScriptLoader::PSDKConfig: the offsets PSDK computed for its window
class FakePsdkConfig
  attr_reader :viewport_offset_x, :viewport_offset_y

  def initialize(x, y)
    @viewport_offset_x = x
    @viewport_offset_y = y
  end
end

Resolution = Struct.new(:x, :y)

require File.expand_path('../../main/ruby/lib/game_area', __dir__)

class TestGameArea < Minitest::Test
  def setup
    @config = FakePsdkConfig.new(107, 0)
    Object.const_set(:PSDK_CONFIG, @config)
  end

  def teardown
    Object.send(:remove_const, :PSDK_CONFIG) if Object.const_defined?(:PSDK_CONFIG)
    Object.send(:remove_const, :Configs) if Object.const_defined?(:Configs)
    Object.send(:remove_const, :Viewport) if Object.const_defined?(:Viewport)
  end

  def configs(width, height)
    display = Struct.new(:game_resolution).new(Resolution.new(width, height))
    Object.const_set(:Configs, Struct.new(:display).new(display))
  end

  def test_the_offsets_become_the_game_area_and_are_set_back_to_0
    configs(320, 240)
    window = LiteRGSS::DisplayWindow.new(534, 240)
    assert_equal [107, 0, 320, 240], PsdkGameArea.apply(window)
    assert_equal [107, 0, 320, 240], window.game_area
    assert_equal [0, 0], [@config.viewport_offset_x, @config.viewport_offset_y],
                 'Viewport.create stops adding them: LiteRGSS does'
  end

  def test_without_configs_the_game_is_the_window_without_the_offsets
    window = LiteRGSS::DisplayWindow.new(534, 300)
    @config = FakePsdkConfig.new(107, 30)
    assert_equal [107, 30, 320, 240], PsdkGameArea.apply(window, @config)
  end

  def test_a_game_as_large_as_its_window_is_left_alone
    window = LiteRGSS::DisplayWindow.new(320, 240)
    assert_nil PsdkGameArea.apply(window, FakePsdkConfig.new(0, 0))
    assert_nil window.game_area
  end

  def test_the_error_window_opened_later_is_left_alone
    configs(320, 240)
    PsdkGameArea.apply(LiteRGSS::DisplayWindow.new(534, 240))
    error_window = LiteRGSS::DisplayWindow.new(960, 480)
    assert_nil PsdkGameArea.apply(error_window)
    assert_nil error_window.game_area
  end

  def test_a_psdk_without_offsets_is_left_alone
    window = LiteRGSS::DisplayWindow.new(534, 240)
    assert_nil PsdkGameArea.apply(window, Object.new)
    assert_nil PsdkGameArea.apply(window, nil)
  end

  def test_a_game_placing_its_viewports_itself_keeps_its_offsets
    Object.const_set(:Viewport, Class.new { @global_offset_x = 50 })
    window = LiteRGSS::DisplayWindow.new(534, 240)
    assert_nil PsdkGameArea.apply(window)
    assert_equal 107, @config.viewport_offset_x
  end

  def test_a_game_larger_than_the_window_left_is_left_alone
    configs(450, 240) # 107 + 450 > 534
    window = LiteRGSS::DisplayWindow.new(534, 240)
    assert_nil PsdkGameArea.apply(window)
    assert_equal 107, @config.viewport_offset_x
  end

  def test_an_error_keeps_psdks_offsets
    window = LiteRGSS::DisplayWindow.new(534, 240)
    window.dispose!
    assert_nil PsdkGameArea.apply(window)
    assert_equal 107, @config.viewport_offset_x
  end

  def test_the_window_psdk_creates_gets_its_game_area
    window_class = Class.new(LiteRGSS::DisplayWindow)
    assert PsdkGameArea.install(window_class)
    assert PsdkGameArea.install(window_class), 'idempotent'
    configs(320, 240)
    window = window_class.new(534, 240)
    assert_equal [107, 0, 320, 240], window.game_area
    assert_equal [320, 240], [window.width, window.height], 'what Graphics.width reads'
  end

  def test_no_hook_without_game_area_in_the_runtime
    window_class = Class.new(LiteRGSS::DisplayWindow) { undef_method :game_area= }
    refute PsdkGameArea.install(window_class)
    refute_includes window_class.singleton_class.ancestors, PsdkGameArea::Creation
  end
end
