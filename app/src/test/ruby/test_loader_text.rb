# Unit tests for Loader::Layers::Text — where the splash draws its footer
# and hub hint.
#
# The splash canvas covers the system bars; RootActivity passes their
# insets (PSDK_LOADER_INSETS, surface pixels). In landscape the navigation
# bar is on a side: the footer guessed a bottom bar, and the hub's
# "Add project" (centred on the canvas, not on the Android "+" FAB) ran into
# it. "Add project" is Android's now.
#
# Run:  ruby app/src/test/ruby/test_loader_text.rb

require 'minitest/autorun'

module LiteRGSS
  class DisplayWindow
    class << self
      attr_accessor :desktop_width
    end
  end

  class Color
    def initialize(*rgba) = (@rgba = rgba)
  end

  class Text
    attr_reader :x, :y, :width, :height, :text
    attr_accessor :size, :fill_color, :outline_color, :outline_thickness, :opacity, :z

    def initialize(_font, _viewport, x, y, width, height, text, _align)
      @x, @y, @width, @height, @text = x, y, width, height, text
    end

    def disposed? = false
  end
end

FakeIcon = Struct.new(:present?, :cta_accent_color)

require File.expand_path('../../main/ruby/loader/layers/text', __dir__)

class TestLoaderText < Minitest::Test
  def teardown
    ENV.delete(Loader::Layers::Text::INSETS_ENV)
  end

  # Redmi Note 13 Pro 5G, landscape: 2712x1220 surface, navigation bar on
  # the right (142 px), cutout on the left (104 px), no bottom bar.
  def landscape_layer(preparing_message: nil)
    LiteRGSS::DisplayWindow.desktop_width = 2712
    ENV[Loader::Layers::Text::INSETS_ENV] = '104,0,142,0'
    build(533, 240, preparing_message)
  end

  def build(width, height, preparing_message)
    text = Loader::Layers::Text.new({}, font_loaded: true, preparing_message: preparing_message)
    text.build(:viewport, width, height, FakeIcon.new(false, [255, 255, 255]))
    text
  end

  def test_the_footer_sits_at_the_bottom_between_the_side_bars
    footer = landscape_layer.instance_variable_get(:@footer)
    scale = 533 / 2712.0
    assert_equal (240 - 8 - 12), footer.y
    assert_equal (104 * scale).to_i, footer.x
    assert_equal (533 - (104 + 142) * scale).to_i, footer.width
  end

  def test_the_footer_clears_a_bottom_navigation_bar
    LiteRGSS::DisplayWindow.desktop_width = 1220
    ENV[Loader::Layers::Text::INSETS_ENV] = '0,0,0,132' # portrait, 3-button bar
    footer = build(240, 533, nil).instance_variable_get(:@footer)
    assert_equal (533 - 132 * 240 / 1220.0 - 8 - 12).to_i, footer.y
  end

  def test_the_hub_leaves_add_project_to_android
    assert_nil landscape_layer.instance_variable_get(:@hint)
  end

  def test_a_preparing_message_is_still_drawn
    hint = landscape_layer(preparing_message: 'Preparing...').instance_variable_get(:@hint)
    assert_equal 'Preparing...', hint.text
  end
end
