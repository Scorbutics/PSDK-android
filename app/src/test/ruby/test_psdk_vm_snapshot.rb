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

  # --- restore! safety and idempotence ---

  def test_restore_is_safe_before_capture
    PSDKVMSnapshot.reset!
    PSDKVMSnapshot.restore!  # must not raise
  end

  def test_restore_preserves_captured_state
    PSDKVMSnapshot.capture!
    PSDKVMSnapshot.restore!
    assert PSDKVMSnapshot.captured?, "restore! must not clear the snapshot"
  end

  # --- restore! actually undoes module mutations ---

  def test_restore_removes_added_instance_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_eval { def added; 42; end }
    PSDKVMSnapshot.restore!
    refute klass.instance_methods(false).include?(:added),
           "restore! should remove the post-capture method"
  end

  def test_restore_removes_added_private_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_eval do
      def hidden; end
      private :hidden
    end
    PSDKVMSnapshot.restore!
    refute klass.private_instance_methods(false).include?(:hidden)
  end

  def test_restore_rebinds_redefined_method
    klass = Class.new { def val; 1; end }
    PSDKVMSnapshot.capture!
    klass.class_eval { def val; 2; end }
    assert_equal 2, klass.new.val
    PSDKVMSnapshot.restore!
    assert_equal 1, klass.new.val, "restore! should bring back the original implementation"
  end

  def test_restore_removes_added_singleton_method
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.define_singleton_method(:cls_method) { 7 }
    PSDKVMSnapshot.restore!
    refute klass.singleton_methods(false).include?(:cls_method)
  end

  def test_restore_removes_added_constant
    mod = Module.new
    PSDKVMSnapshot.capture!
    mod.const_set(:NEW, 42)
    PSDKVMSnapshot.restore!
    refute mod.const_defined?(:NEW, false)
  end

  def test_restore_resets_changed_constant
    mod = Module.new
    mod.const_set(:VAL, :original)
    PSDKVMSnapshot.capture!
    silence_warnings { mod.const_set(:VAL, :mutated) }
    PSDKVMSnapshot.restore!
    assert_equal :original, mod.const_get(:VAL)
  end

  def test_restore_removes_added_class_variable
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.class_variable_set(:@@thing, 1)
    PSDKVMSnapshot.restore!
    refute klass.class_variable_defined?(:@@thing)
  end

  def test_restore_removes_added_class_ivar
    klass = Class.new
    PSDKVMSnapshot.capture!
    klass.instance_variable_set(:@meta, "hello")
    PSDKVMSnapshot.restore!
    refute klass.instance_variable_defined?(:@meta)
  end

  def test_restore_removes_added_loaded_features
    marker = "/__psdk_vm_snapshot_restore_marker__.rb"
    PSDKVMSnapshot.capture!
    $LOADED_FEATURES << marker
    PSDKVMSnapshot.restore!
    refute_includes $LOADED_FEATURES, marker
  ensure
    $LOADED_FEATURES.delete(marker)
  end

  def test_restore_clears_added_global
    PSDKVMSnapshot.capture!
    eval("$psdk_test_added_global = 'set'")
    PSDKVMSnapshot.restore!
    assert_nil eval("$psdk_test_added_global"),
               "restore! should nil out globals introduced after capture"
  end

  def test_restore_resets_changed_global
    eval("$psdk_test_changed_global = 'before'")
    PSDKVMSnapshot.capture!
    eval("$psdk_test_changed_global = 'after'")
    PSDKVMSnapshot.restore!
    assert_equal 'before', eval("$psdk_test_changed_global")
  end

  # --- The killer test: the actual PSDK alias-chain bug, twice over. ---
  #
  # Round 1: PSDK applies the alias chain on top of the original. Works.
  # Restore: chain is unwound; only the original `initialize` remains.
  # Round 2: PSDK applies the alias chain AGAIN (simulating a fresh
  # `require './Game.rb'` on the same VM). If restore worked, the chain
  # is depth-1 again — no infinite recursion. If restore failed, the new
  # `_original_initialize` alias would point at the previous wrapper and
  # Round 2's `initialize.new` would stack-overflow.
  def test_restore_fixes_alias_chain_across_reloads
    klass = Class.new { def initialize; @x = 1; end }
    PSDKVMSnapshot.capture!

    apply_alias_wrapper = lambda do
      klass.class_eval do
        alias_method :_original_initialize, :initialize
        def initialize
          _original_initialize
          @y = (@y || 0) + 1
        end
      end
    end

    # Round 1.
    apply_alias_wrapper.call
    obj1 = klass.new
    assert_equal 1, obj1.instance_variable_get(:@x)
    assert_equal 1, obj1.instance_variable_get(:@y)

    # Restore. The wrapper and the alias should both vanish.
    # NB: #initialize is private by default in Ruby, so it lives in
    # private_instance_methods, not instance_methods.
    PSDKVMSnapshot.restore!
    assert_equal [], klass.instance_methods(false),
                 "no public methods should remain on klass after restore"
    assert_equal [:initialize], klass.private_instance_methods(false),
                 "only the captured :initialize should remain"
    obj_mid = klass.new
    assert_equal 1, obj_mid.instance_variable_get(:@x)
    refute obj_mid.instance_variable_defined?(:@y),
           "restored initialize should not run the wrapper's body"

    # Round 2 — the moment of truth. Without restore, this re-aliases on
    # top of the round-1 wrapper and `klass.new` infinite-loops.
    apply_alias_wrapper.call
    obj2 = nil
    # Wrap in Timeout in case the bug regresses — we'd rather fail loudly.
    require 'timeout'
    Timeout.timeout(2) { obj2 = klass.new }
    assert_equal 1, obj2.instance_variable_get(:@x)
    assert_equal 1, obj2.instance_variable_get(:@y),
                 "round-2 wrapper should run exactly once (chain length 1)"
  end

  # --- Native unprepend tests (skip if extension not built) ---

  def native_required
    skip "native ext not loaded — build with " \
         "`cd app/src/main/ruby/ext/psdk_vm_snapshot_native && ruby extconf.rb && make`" \
      unless PSDKVMSnapshot::NATIVE_AVAILABLE
  end

  def test_native_unprepend_returns_false_when_not_in_chain
    native_required
    klass = Class.new
    mod = Module.new
    refute PSDKVMSnapshot::Native.unprepend(klass, mod),
           "unprepend should return false when mod isn't in chain"
  end

  def test_native_unprepend_returns_true_after_prepend
    native_required
    klass = Class.new
    mod = Module.new
    klass.prepend(mod)
    assert PSDKVMSnapshot::Native.unprepend(klass, mod)
  end

  def test_native_unprepend_removes_from_ancestors
    native_required
    klass = Class.new
    mod = Module.new { def label; "mixed"; end }
    klass.prepend(mod)
    assert_includes klass.ancestors, mod
    PSDKVMSnapshot::Native.unprepend(klass, mod)
    refute_includes klass.ancestors, mod
  end

  def test_native_unprepend_changes_method_dispatch
    native_required
    klass = Class.new { def label; "base"; end }
    mod = Module.new { def label; "mixed"; end }
    klass.prepend(mod)
    assert_equal "mixed", klass.new.label
    PSDKVMSnapshot::Native.unprepend(klass, mod)
    assert_equal "base", klass.new.label, "dispatch should fall through to klass after unprepend"
  end

  def test_native_unprepend_works_for_include
    native_required
    klass = Class.new
    mod = Module.new { def helper; 42; end }
    klass.include(mod)
    assert_equal 42, klass.new.helper
    assert PSDKVMSnapshot::Native.unprepend(klass, mod),
           "include creates an IClass too; unprepend should handle it"
    refute klass.new.respond_to?(:helper)
  end

  # The killer test for option 2 over option 1: a shared mixin is NOT
  # destroyed when one of its targets unprepends. The Ruby-only
  # neutralize approach (remove_method on the mixin) would have nuked
  # `bar` too. Chain surgery only touches one chain.
  def test_native_unprepend_does_not_affect_siblings
    native_required
    mixin = Module.new { def tag; "mixed"; end }
    foo = Class.new { def tag; "foo"; end }
    bar = Class.new { def tag; "bar"; end }
    foo.prepend(mixin)
    bar.prepend(mixin)
    assert_equal "mixed", foo.new.tag
    assert_equal "mixed", bar.new.tag

    PSDKVMSnapshot::Native.unprepend(foo, mixin)

    assert_equal "foo",   foo.new.tag, "foo should no longer see mixin"
    assert_equal "mixed", bar.new.tag, "bar's chain is independent and must not regress"
  end

  def test_native_unprepend_handles_multiple_prepends
    native_required
    klass = Class.new { def stack; ["base"]; end }
    a = Module.new { def stack; super + ["a"]; end }
    b = Module.new { def stack; super + ["b"]; end }
    klass.prepend(a)
    klass.prepend(b)
    assert_equal ["base", "a", "b"], klass.new.stack

    PSDKVMSnapshot::Native.unprepend(klass, a)
    assert_equal ["base", "b"], klass.new.stack

    PSDKVMSnapshot::Native.unprepend(klass, b)
    assert_equal ["base"], klass.new.stack
  end

  def test_native_unprepend_raises_on_wrong_arg_types
    native_required
    assert_raises(TypeError) { PSDKVMSnapshot::Native.unprepend("string", Module.new) }
    assert_raises(TypeError) { PSDKVMSnapshot::Native.unprepend(Class.new, 42) }
  end

  # --- Integration: PSDKVMSnapshot.restore! actually undoes prepends ---

  def test_restore_undoes_new_module_prepend
    native_required
    klass = Class.new { def greet; "hi"; end }
    PSDKVMSnapshot.capture!
    mixin = Module.new { def greet; "PWNED"; end }
    klass.prepend(mixin)
    assert_equal "PWNED", klass.new.greet
    PSDKVMSnapshot.restore!
    assert_equal "hi", klass.new.greet, "restore should splice out the post-capture prepend"
    refute_includes klass.ancestors, mixin
  end

  def test_restore_undoes_alias_chain_built_via_prepend
    native_required
    klass = Class.new { def initialize; @x = 1; end }
    PSDKVMSnapshot.capture!

    apply_prepend_wrapper = lambda do
      m = Module.new do
        def initialize
          super
          @y = (@y || 0) + 1
        end
      end
      klass.prepend(m)
    end

    apply_prepend_wrapper.call
    obj1 = klass.new
    assert_equal 1, obj1.instance_variable_get(:@x)
    assert_equal 1, obj1.instance_variable_get(:@y)

    PSDKVMSnapshot.restore!
    obj_mid = klass.new
    assert_equal 1, obj_mid.instance_variable_get(:@x)
    refute obj_mid.instance_variable_defined?(:@y),
           "restore should remove the prepended wrapper entirely"

    # Round 2 — re-apply the same wrapper. Without unprepend, this
    # would stack two IClasses, each calling super, and the chain
    # would keep working but accumulate. With unprepend, we're back
    # to a one-step chain.
    apply_prepend_wrapper.call
    require 'timeout'
    obj2 = nil
    Timeout.timeout(2) { obj2 = klass.new }
    assert_equal 1, obj2.instance_variable_get(:@x)
    assert_equal 1, obj2.instance_variable_get(:@y)
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
