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
    attr_reader :x, :y, :width, :height
    attr_accessor :text
    attr_accessor :size, :fill_color, :outline_color, :outline_thickness, :opacity, :z

    def initialize(_font, _viewport, x, y, width, height, text, _align)
      @x, @y, @width, @height, @text = x, y, width, height, text
    end

    def disposed? = false
  end
end

FakeIcon = Struct.new(:present?, :cta_accent_color, :center_y, :icon_h)

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

  def build(width, height, preparing_message, font: true)
    text = Loader::Layers::Text.new({}, font_loaded: font, preparing_message: preparing_message)
    text.build(:viewport, width, height, FakeIcon.new(false, [255, 255, 255]))
    text
  end

  def test_the_footer_sits_at_the_bottom_between_the_side_bars
    footer = landscape_layer.instance_variable_get(:@footer)
    scale = 533 / 2712.0
    assert_equal (240 - 8 - Loader::Layers::Text::FOOTER_HEIGHT), footer.y
    assert_equal (104 * scale).to_i, footer.x
    assert_equal (533 - (104 + 142) * scale).to_i, footer.width
  end

  def test_the_footer_clears_a_bottom_navigation_bar
    LiteRGSS::DisplayWindow.desktop_width = 1220
    ENV[Loader::Layers::Text::INSETS_ENV] = '0,0,0,132' # portrait, 3-button bar
    footer = build(240, 533, nil).instance_variable_get(:@footer)
    assert_equal (533 - 132 * 240 / 1220.0 - 8 - Loader::Layers::Text::FOOTER_HEIGHT).to_i, footer.y
  end

  def test_the_hub_leaves_add_project_to_android
    assert_nil landscape_layer.instance_variable_get(:@hint)
  end

  def test_a_preparing_message_is_still_drawn
    hint = landscape_layer(preparing_message: 'Preparing...').instance_variable_get(:@hint)
    assert_equal 'Preparing...', hint.text
  end

  # A small APK's setup: the host's statuses (loader.status) replace the
  # preparing message, their second line under it
  def test_a_status_replaces_the_preparing_message
    layer = landscape_layer(preparing_message: 'Preparing the game')
    layer.update_status("Downloading the game… 42%\n126 MB / 300 MB")
    assert_equal 'Downloading the game… 42%', layer.instance_variable_get(:@hint).text
    assert_equal '126 MB / 300 MB', layer.instance_variable_get(:@hint_detail).text
    layer.update_status('Checking the game…')
    assert_equal 'Checking the game…', layer.instance_variable_get(:@hint).text
    assert_equal '', layer.instance_variable_get(:@hint_detail).text
  end

  # loader.rb's "Tap logo to compile" CTA goes to the status line only
  def test_the_preparing_hint_is_no_status_line
    refute landscape_layer(preparing_message: 'Preparing the game').status_present?
  end

  def test_a_status_without_a_hint_or_status_line_is_ignored
    landscape_layer.update_status('Downloading the game… 1%')
  end

  # A small APK with its game's splash: the download in the status line
  def test_a_status_with_a_detail_under_the_status_line
    LiteRGSS::DisplayWindow.desktop_width = 1220
    text = Loader::Layers::Text.new({ title: 'Lethalmon', version: 'v2.24' }, font_loaded: true)
    text.build(:viewport, 240, 533, FakeIcon.new(true, [90, 170, 255], 266, 64))
    text.update_status("Downloading Lethalmon v2.24... 35%\n221 MB / 633 MB")
    assert_equal 'Downloading Lethalmon v2.24... 35%', text.instance_variable_get(:@status).text
    assert_equal '221 MB / 633 MB', text.instance_variable_get(:@status_detail).text
    text.update_status('‹ Tap logo to compile ›')
    assert_equal '', text.instance_variable_get(:@status_detail).text
  end
end

