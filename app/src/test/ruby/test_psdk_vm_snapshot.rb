# Unit tests for PSDKVMSnapshot — Stage 1 (capture + diff, no restore).
#
# These tests run on plain MRI (no embedded VM, no LiteRGSS). Each test
# resets the snapshot, captures, mutates, and asserts the diff matches.
#
# Run:  ruby app/src/test/ruby/test_psdk_vm_snapshot.rb

require 'minitest/autorun'

LIB = File.expand_path('../../main/ruby', __dir__)
require File.join(LIB, 'psdk_vm_snapshot')

class TestPSDKVMSnapshot < Minitest::Test
  class SilentLogger
    def info(_); end
    def error(_); end
  end

  def setup
    @prev_logger = PSDKVMSnapshot.logger
    PSDKVMSnapshot.logger = SilentLogger.new
    PSDKVMSnapshot.reset!
  end

  def teardown
    PSDKVMSnapshot.reset!
    PSDKVMSnapshot.logger = @prev_logger
  end

  # --- capture! / reset! / captured? ---

  def test_capture_sets_captured_flag
    refute PSDKVMSnapshot.captured?
    PSDKVMSnapshot.capture!
    assert PSDKVMSnapshot.captured?
  end

  def test_capture_is_idempotent
    PSDKVMSnapshot.capture!
    snap1 = PSDKVMSnapshot.instance_variable_get(:@snapshot)
    PSDKVMSnapshot.capture!
    snap2 = PSDKVMSnapshot.instance_variable_get(:@snapshot)
    assert_same snap1, snap2, "second capture! must not rebuild the snapshot"
  end

  def test_reset_clears_captured
    PSDKVMSnapshot.capture!
    PSDKVMSnapshot.reset!
    refute PSDKVMSnapshot.captured?
  end

  def test_diff_raises_before_capture
    assert_raises(RuntimeError) { PSDKVMSnapshot.diff }
  end

  # --- new modules ---

  def test_diff_detects_new_class
    PSDKVMSnapshot.capture!
    klass = Class.new
    d = PSDKVMSnapshot.diff
    assert_includes d[:new_modules], klass
  end

  def test_diff_detects_new_module
    PSDKVMSnapshot.capture!
    mod = Module.new
    d = PSDKVMSnapshot.diff
    assert_includes d[:new_modules], mod
  end

  def test_diff_excludes_pre_existing_classes
    pre = Class.new
    PSDKVMSnapshot.capture!
    d = PSDKVMSnapshot.diff
    refute_includes d[:new_modules], pre
  end

  # --- method changes ---

  def test_diff_detects_new_instance_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_eval { def foo; end }
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md, "expected klass to appear in modified_modules"
    assert_includes md[:added_instance_methods], :foo
  end

  def test_diff_detects_new_private_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_eval do
      def hidden; end
      private :hidden
    end
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:added_instance_methods], :hidden
  end

  def test_diff_detects_redefined_method
    klass = Class.new { def bar; 1; end }
    PSDKVMSnapshot.capture!
    klass.class_eval { def bar; 2; end }
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:changed_instance_methods], :bar
  end

  def test_diff_detects_new_singleton_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.define_singleton_method(:baz) { 42 }
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:added_singleton_methods], :baz
  end

  # --- The actual PSDK alias-chain pattern this spike exists to detect ---

  def test_diff_detects_alias_chain_pattern
    klass = Class.new { def initialize; @x = 1; end }
    PSDKVMSnapshot.capture!
    klass.class_eval do
      alias_method :_original_initialize, :initialize
      def initialize; _original_initialize; @y = 2; end
    end
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:added_instance_methods], :_original_initialize,
                    "alias_method introduces a new method name"
    assert_includes md[:changed_instance_methods], :initialize,
                    "initialize was redefined to wrap the original"
  end

  # --- constants ---

  def test_diff_detects_new_constant
    mod = Module.new
    PSDKVMSnapshot.capture!
    mod.const_set(:NEW_CONST, 42)
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][mod]
    refute_nil md
    assert_includes md[:added_constants], :NEW_CONST
  end

  def test_diff_detects_changed_constant
    mod = Module.new
    mod.const_set(:VAL, [1, 2, 3])
    PSDKVMSnapshot.capture!
    # Reassign to a new object — same name, different identity.
    silence_warnings { mod.const_set(:VAL, [4, 5, 6]) }
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][mod]
    refute_nil md
    assert_includes md[:changed_constants], :VAL
  end

  # --- class variables / class ivars ---

  def test_diff_detects_new_class_variable
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_variable_set(:@@thing, 1)
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:added_class_variables], :@@thing
  end

  def test_diff_detects_new_class_ivar
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.instance_variable_set(:@meta, "hello")
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert_includes md[:added_class_ivars], :@meta
  end

  # --- ancestors (include/prepend detection) ---

  def test_diff_detects_include
    klass = Class.new
    mixin = Module.new { def shared; end }
    PSDKVMSnapshot.capture!
    klass.include(mixin)
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert md[:ancestors_changed], "include should flip ancestors_changed"
  end

  def test_diff_detects_prepend
    klass = Class.new { def foo; 1; end }
    mixin = Module.new { def foo; super + 1; end }
    PSDKVMSnapshot.capture!
    klass.prepend(mixin)
    d = PSDKVMSnapshot.diff
    md = d[:modified_modules][klass]
    refute_nil md
    assert md[:ancestors_changed], "prepend should flip ancestors_changed"
  end

  # --- $LOADED_FEATURES + globals ---

  def test_diff_detects_new_loaded_feature
    PSDKVMSnapshot.capture!
    marker = "/__psdk_vm_snapshot_test_marker__.rb"
    $LOADED_FEATURES << marker
    begin
      d = PSDKVMSnapshot.diff
      assert_includes d[:new_loaded_features], marker
    ensure
      $LOADED_FEATURES.delete(marker)
    end
  end

  def test_diff_detects_new_global_var
    PSDKVMSnapshot.capture!
    eval("$psdk_vm_snapshot_test_new = 42")
    d = PSDKVMSnapshot.diff
    assert_includes d[:new_globals], :$psdk_vm_snapshot_test_new
  end

  def test_diff_detects_changed_global_var
    eval("$psdk_vm_snapshot_test_chg = 'before'")
    PSDKVMSnapshot.capture!
    eval("$psdk_vm_snapshot_test_chg = 'after'")
    d = PSDKVMSnapshot.diff
    assert_includes d[:changed_globals], :$psdk_vm_snapshot_test_chg
  end

  # --- restore! stub safety ---

  def test_restore_is_safe_before_capture
    PSDKVMSnapshot.reset!
    PSDKVMSnapshot.restore!  # must not raise
  end

  def test_restore_after_capture_is_noop
    PSDKVMSnapshot.capture!
    PSDKVMSnapshot.restore!  # stub; just check it doesn't raise
  end

  private

  def silence_warnings
    old_verbose = $VERBOSE
    $VERBOSE = nil
    yield
  ensure
    $VERBOSE = old_verbose
  end
end
