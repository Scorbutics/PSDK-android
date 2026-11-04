# Unit tests for GameWarnings: the game's Ruby warnings, by base APK.
#
# Run:  ruby app/src/test/ruby/test_game_warnings.rb

require 'minitest/autorun'
require File.expand_path('../../main/ruby/lib/game_warnings', __dir__)

class TestGameWarnings < Minitest::Test
  def setup
    @verbose = $VERBOSE
  end

  def teardown
    $VERBOSE = @verbose
  end

  # Kernel#warn and a constant reassigned, as the game's scripts do them
  def game_warnings
    _, err = capture_io do
      warn '[TouchOverlay] disabled: LiteRGSS::Error: Disposed Object'
      Object.send(:remove_const, :GameWarningsProbe) if Object.const_defined?(:GameWarningsProbe, false)
      eval('GameWarningsProbe = 1; GameWarningsProbe = 2', TOPLEVEL_BINDING, 'Engine.rb', 1)
    end
    err
  end

  def test_a_release_base_apk_silences_the_games_warnings
    previous = GameWarnings.install(GameWarnings::DEBUG_ENV => '0')
    assert_nil $VERBOSE
    assert_empty game_warnings
    GameWarnings.restore(previous)
    assert_equal @verbose, $VERBOSE
  end

  def test_no_app_setting_is_a_release_one
    refute GameWarnings.debug?({})
    GameWarnings.install({})
    assert_nil $VERBOSE
  end

  def test_a_debug_base_apk_keeps_all_but_constant_reassignments
    $VERBOSE = false
    GameWarnings.install(GameWarnings::DEBUG_ENV => '1')
    assert_equal false, $VERBOSE
    err = game_warnings
    assert_match(/\[TouchOverlay\] disabled/, err)
    refute_match(/already initialized constant/, err)
    refute_match(/previous definition of/, err)
    # Any other interpreter warning still shows
    _, other = capture_io { Warning.warn("Engine.rb:3: warning: something else\n") }
    assert_match(/something else/, other)
  end
end
