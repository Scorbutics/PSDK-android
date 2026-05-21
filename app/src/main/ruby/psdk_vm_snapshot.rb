# psdk_vm_snapshot.rb
#
# Spike Stage 1: capture a baseline of every Ruby class/module's
# methods/constants/vars + globals + $LOADED_FEATURES, then diff against
# current state. restore! is a stub here — Stage 2 will implement it.
#
# The goal of this stage is purely diagnostic: get an inventory of what
# PSDK actually mutates between `capture!` and game-finish, so we know
# what restore! has to handle.
#
# Usage:
#   PSDKVMSnapshot.capture!          # idempotent; baseline frozen at first call
#   ... game runs and monkey-patches everything ...
#   PSDKVMSnapshot.log_diff!         # prints summary via the logger
#   PSDKVMSnapshot.restore!          # Stage-1 stub: logs intent, no-op
#
# Designed to be testable on plain MRI without VMLogger / safe_runner; the
# logger picks up VMLogger if defined, else falls back to stderr.

# Guard the whole module body so re-evaluating this file on a reused VM
# (loader/start scripts get re-eval'd on each invocation) doesn't re-run
# the NATIVE_AVAILABLE / GLOBAL_NAME_RE constant assignments — those
# warn "already initialized constant" because they're inside the
# captured baseline and restore! deliberately keeps them. The auto-
# capture call at the bottom still fires; capture! is idempotent.
#
# We can't guard on `defined?(PSDKVMSnapshot)` — the C-side host
# extension creates the module (as the parent of PSDKVMSnapshot::Native)
# before any Ruby runs, so that constant is always defined on first eval
# too. Probe for one of our own constants instead — NATIVE_AVAILABLE is
# the first Ruby-defined one in the module body, so its presence means
# this file has already finished loading on a prior pass.
unless defined?(PSDKVMSnapshot::NATIVE_AVAILABLE)

# Resolve stdlib / gem install directories so restore can tell library
# code from app code. We must NEVER wipe stdlib state (constants like
# JSON, $LOADED_FEATURES entries for json/ext.so etc.): C extensions
# can't be re-dlopen'd safely, and Ruby's require-cache is the only
# thing that prevents the second-session re-require from blowing up.
# PSDK's own code, in contrast, must be reaped — that's the whole point
# of restore.
require 'rbconfig'

module PSDKVMSnapshot
  # Primary source: $LOAD_PATH at module-load time. This is runtime truth —
  # Ruby couldn't find any stdlib file to require it without the containing
  # directory being in $LOAD_PATH. RbConfig::CONFIG values are also folded
  # in defensively, but on a cross-compiled Ruby (Android) those often
  # carry build-host paths, not the device's actual install dir, so they
  # can't be the sole source.
  #
  # Captured at module-eval (before any app code mutates $LOAD_PATH), so
  # only Ruby's default stdlib/gem entries are picked up — exactly the
  # set we want to mark as "do-not-reap".
  STDLIB_PATH_PREFIXES = begin
    prefixes = $LOAD_PATH.dup
    if defined?(RbConfig::CONFIG)
      %w[rubylibdir sitelibdir vendorlibdir archdir sitearchdir vendorarchdir rubyarchdir gemdir].each do |k|
        v = RbConfig::CONFIG[k]
        prefixes << v if v && !v.empty?
      end
    end
    # Keep both the expanded form (matches $LOAD_PATH-derived paths
    # verbatim) AND the realpath form (matches $LOADED_FEATURES, which on
    # Android stores canonical /data/data/... while $LOAD_PATH uses the
    # /data/user/0/... symlink form). File.realpath raises ENOENT for
    # cross-compile-baked paths that don't exist on device; in that case
    # the expanded form is the only useful entry.
    prefixes.compact.flat_map do |p|
      expanded = File.expand_path(p)
      [expanded, (File.realpath(expanded) rescue nil)]
    end.compact.uniq.freeze
  end

  # TEMP DIAGNOSTIC: print the resolved stdlib prefixes once at load time.
  # Remove after the cross-compile path issue is understood.
  $stderr.puts("[PSDKVMSnapshot][DIAG] STDLIB_PATH_PREFIXES (#{STDLIB_PATH_PREFIXES.size}):")
  STDLIB_PATH_PREFIXES.each { |p| $stderr.puts("[PSDKVMSnapshot][DIAG]   #{p}") }
  $stderr.puts("[PSDKVMSnapshot][DIAG] $LOAD_PATH at this point (#{$LOAD_PATH.size}):")
  $LOAD_PATH.each { |p| $stderr.puts("[PSDKVMSnapshot][DIAG]   #{p}") }

  class StderrLogger
    def info(msg); $stderr.puts("[I] #{msg}"); end
    def error(msg); $stderr.puts("[E] #{msg}"); end
  end

  # Look for the native unprepend extension. Lives in
  # litergss-everywhere/external/ruby-psdk-vm-snapshot — it's
  # cross-platform-built there and reused across consumers. The on-device
  # path: host C code statically links the .a and calls Init_* via
  # api.set_custom_ext_init() before any Ruby script runs, so by the time
  # this constant is evaluated PSDKVMSnapshot::Native already exists.
  # Dev-box path: developer can point ENV['PSDK_VM_SNAPSHOT_NATIVE_LIB']
  # at a built .so, or rely on the sibling-checkout convention.
  NATIVE_AVAILABLE =
    if defined?(PSDKVMSnapshot::Native) && PSDKVMSnapshot::Native.respond_to?(:unprepend)
      true
    else
      candidate_paths = [
        ENV['PSDK_VM_SNAPSHOT_NATIVE_LIB'],
        # Sibling litergss-everywhere checkout (dev-box convention).
        File.expand_path(
          '../../../../litergss-everywhere/external/ruby-psdk-vm-snapshot/psdk_vm_snapshot_native',
          __FILE__,
        ),
      ].compact
      loaded = candidate_paths.any? do |path|
        begin
          require path
          true
        rescue LoadError
          false
        end
      end
      loaded
    end

  class << self
    # Pluggable logger. Tests override with a silent logger.
    def logger
      @logger ||= (defined?(VMLogger) ? VMLogger : StderrLogger.new)
    end
    attr_writer :logger

    def captured?
      !@snapshot.nil?
    end

    # Capture state of every reachable Module. Idempotent — subsequent
    # calls are no-ops, so it's safe to call this unconditionally at the
    # start of every game session: the first call wins.
    def capture!
      return self if captured?

      modules_state = {}
      each_relevant_module do |mod|
        modules_state[mod] = capture_module(mod)
      end

      @snapshot = {
        modules: modules_state,
        loaded_features: $LOADED_FEATURES.dup,
        global_vars: capture_globals,
      }

      logger.info("[PSDKVMSnapshot] Captured #{modules_state.size} modules, " \
                  "#{@snapshot[:loaded_features].size} loaded features, " \
                  "#{@snapshot[:global_vars].size} globals")
      self
    end

    # Forget the snapshot. Used by tests; not called from production code.
    def reset!
      @snapshot = nil
      self
    end

    # Returns a structured Hash describing what's changed since capture.
    #   :new_modules           => [Module, ...]
    #   :removed_modules       => [Module, ...]   (rare — modules don't usually die)
    #   :modified_modules      => { Module => diff_hash }
    #   :new_loaded_features   => [String, ...]
    #   :new_globals           => [Symbol, ...]   (names only)
    #   :changed_globals       => [Symbol, ...]
    def diff
      raise "PSDKVMSnapshot.capture! has not been called" unless captured?

      current_modules = []
      each_relevant_module { |mod| current_modules << mod }
      current_set = current_modules.to_set_compat

      new_modules = current_modules - @snapshot[:modules].keys
      removed_modules = @snapshot[:modules].keys.reject { |m| current_set.include?(m) }

      module_diffs = {}
      @snapshot[:modules].each do |mod, snap|
        next unless current_set.include?(mod)
        d = diff_module(mod, snap)
        module_diffs[mod] = d unless d.empty?
      end

      current_globals = capture_globals
      new_globals = current_globals.keys - @snapshot[:global_vars].keys
      changed_globals = (current_globals.keys & @snapshot[:global_vars].keys).reject do |name|
        @snapshot[:global_vars][name].equal?(current_globals[name])
      end

      {
        new_modules: new_modules,
        removed_modules: removed_modules,
        modified_modules: module_diffs,
        new_loaded_features: $LOADED_FEATURES - @snapshot[:loaded_features],
        new_globals: new_globals,
        changed_globals: changed_globals,
      }
    end

    # Pretty-print the diff via the logger. Caps output per category so
    # we don't drown the log; full diff is still available via #diff.
    def log_diff!(cap: 20)
      d = diff
      logger.info("[PSDKVMSnapshot] === Diff Report ===")

      logger.info("[PSDKVMSnapshot] new_modules: #{d[:new_modules].size}")
      d[:new_modules].first(cap).each { |m| logger.info("[PSDKVMSnapshot]   + #{format_mod(m)}") }
      log_overflow(d[:new_modules].size, cap)

      logger.info("[PSDKVMSnapshot] removed_modules: #{d[:removed_modules].size}")
      d[:removed_modules].first(cap).each { |m| logger.info("[PSDKVMSnapshot]   - #{format_mod(m)}") }

      logger.info("[PSDKVMSnapshot] modified_modules: #{d[:modified_modules].size}")
      d[:modified_modules].first(cap).each do |mod, md|
        logger.info("[PSDKVMSnapshot]   ~ #{format_mod(mod)}: #{summarize_module_diff(md)}")
      end
      log_overflow(d[:modified_modules].size, cap)

      logger.info("[PSDKVMSnapshot] new_loaded_features: #{d[:new_loaded_features].size}")
      d[:new_loaded_features].first(cap).each { |f| logger.info("[PSDKVMSnapshot]   + #{f}") }
      log_overflow(d[:new_loaded_features].size, cap)

      logger.info("[PSDKVMSnapshot] new_globals: #{d[:new_globals].size}  changed_globals: #{d[:changed_globals].size}")
      d[:new_globals].first(cap).each { |g| logger.info("[PSDKVMSnapshot]   + #{g}") }
      d[:changed_globals].first(cap).each { |g| logger.info("[PSDKVMSnapshot]   ~ #{g}") }

      logger.info("[PSDKVMSnapshot] === End Diff Report ===")
      d
    end

    # Roll the VM back to the captured baseline. Idempotent: calling
    # restore! when state already matches the snapshot is a no-op.
    #
    # The snapshot itself is NOT cleared by restore! — captured? stays
    # true so the next session can restore again without recapturing.
    #
    # Errors are caught per-item and reported in aggregate; a single
    # broken class doesn't abort the whole restore.
    def restore!
      unless captured?
        logger.info("[PSDKVMSnapshot] restore! called before capture!; no-op")
        return self
      end

      errors = []

      # Wrap the whole pass in $VERBOSE=nil: redefining methods,
      # re-setting constants, and clearing globals all trigger warnings
      # that would otherwise spam logcat once per touched name.
      old_verbose = $VERBOSE
      $VERBOSE = nil
      begin
        @snapshot[:modules].each do |mod, snap|
          restore_module(mod, snap, errors)
        end
        restore_globals(errors)
        restore_loaded_features(errors)
      ensure
        $VERBOSE = old_verbose
      end

      errors.each { |msg| logger.error("[PSDKVMSnapshot] restore: #{msg}") }
      logger.info("[PSDKVMSnapshot] restore! complete (#{errors.size} error(s))")

      # Reclaim orphaned classes / closures / instances. Without this
      # they linger until the next natural GC, which on Android may be
      # much later than the user starting the next game session.
      GC.start

      self
    end

    private

    # Yields every Module/Class we want to track, skipping our own internals.
    def each_relevant_module
      ObjectSpace.each_object(Module) do |mod|
        next if mod.equal?(PSDKVMSnapshot)
        next if mod.equal?(PSDKVMSnapshot::StderrLogger)
        yield mod
      end
    end

    def capture_module(mod)
      {
        instance_methods: capture_methods(mod, :instance),
        singleton_methods: capture_methods(mod, :singleton),
        constants: capture_constants(mod),
        class_variables: capture_class_variables(mod),
        class_ivars: capture_class_ivars(mod),
        ancestors: mod.ancestors.dup,
      }
    end

    def capture_methods(mod, kind)
      result = {}
      names, owner = case kind
                     when :instance
                       [
                         mod.instance_methods(false) +
                           mod.private_instance_methods(false) +
                           mod.protected_instance_methods(false),
                         mod,
                       ]
                     when :singleton
                       sc = mod.singleton_class
                       [
                         sc.instance_methods(false) +
                           sc.private_instance_methods(false) +
                           sc.protected_instance_methods(false),
                         sc,
                       ]
                     end
      names.each do |name|
        begin
          result[name] = owner.instance_method(name)
        rescue NameError
          # Method went away between listing and lookup. Rare; skip.
        end
      end
      result
    end

    def capture_constants(mod)
      consts = {}
      # Silence "constant X is deprecated" warnings emitted by stdlib's
      # `const_added` hooks when we read seemingly-mundane constants.
      old_verbose = $VERBOSE
      $VERBOSE = nil
      begin
        mod.constants(false).each do |name|
          begin
            consts[name] = mod.const_get(name, false)
          rescue NameError, LoadError, StandardError
            consts[name] = :__unreadable__
          end
        end
      ensure
        $VERBOSE = old_verbose
      end
      consts
    end

    def capture_class_variables(mod)
      vars = {}
      mod.class_variables.each do |name|
        begin
          vars[name] = mod.class_variable_get(name)
        rescue NameError
        end
      end
      vars
    end

    def capture_class_ivars(mod)
      vars = {}
      mod.instance_variables.each do |name|
        vars[name] = mod.instance_variable_get(name)
      end
      vars
    end

    # Captures NAMES (and identity-references) of globals. We hold the values
    # by reference, not by deep-copy — diff treats "same object" as unchanged.
    def capture_globals
      globals = {}
      # Silence "variable $= is no longer effective" and similar warnings
      # emitted when we eval deprecated specials by name.
      old_verbose = $VERBOSE
      $VERBOSE = nil
      begin
        global_variables.each do |name|
          begin
            # eval is the only way to read an arbitrary $-variable by symbol.
            globals[name] = eval(name.to_s)
          rescue StandardError
            # $-special-globals (e.g., $~ outside a match scope) can raise.
          end
        end
      ensure
        $VERBOSE = old_verbose
      end
      globals
    end

    def diff_module(mod, snap)
      d = {}

      now_im = capture_methods(mod, :instance)
      now_sm = capture_methods(mod, :singleton)
      now_c = capture_constants(mod)
      now_cv = capture_class_variables(mod)
      now_iv = capture_class_ivars(mod)
      now_anc = mod.ancestors

      a, r, c = method_diff(now_im, snap[:instance_methods])
      d[:added_instance_methods] = a unless a.empty?
      d[:removed_instance_methods] = r unless r.empty?
      d[:changed_instance_methods] = c unless c.empty?

      a, r, c = method_diff(now_sm, snap[:singleton_methods])
      d[:added_singleton_methods] = a unless a.empty?
      d[:removed_singleton_methods] = r unless r.empty?
      d[:changed_singleton_methods] = c unless c.empty?

      added_c = now_c.keys - snap[:constants].keys
      removed_c = snap[:constants].keys - now_c.keys
      changed_c = (now_c.keys & snap[:constants].keys).reject do |k|
        snap[:constants][k].equal?(now_c[k])
      end
      d[:added_constants] = added_c unless added_c.empty?
      d[:removed_constants] = removed_c unless removed_c.empty?
      d[:changed_constants] = changed_c unless changed_c.empty?

      added_cv = now_cv.keys - snap[:class_variables].keys
      d[:added_class_variables] = added_cv unless added_cv.empty?

      added_iv = now_iv.keys - snap[:class_ivars].keys
      d[:added_class_ivars] = added_iv unless added_iv.empty?

      d[:ancestors_changed] = true if now_anc != snap[:ancestors]

      d
    end

    def method_diff(now_hash, snap_hash)
      added = now_hash.keys - snap_hash.keys
      removed = snap_hash.keys - now_hash.keys
      # UnboundMethod#== compares by underlying definition; if `def foo`
      # ran a second time the iseq differs and the methods compare !=.
      changed = (now_hash.keys & snap_hash.keys).reject do |name|
        snap_hash[name] == now_hash[name]
      end
      [added, removed, changed]
    end

    def summarize_module_diff(md)
      bits = []
      bits << "+#{md[:added_instance_methods].size}im" if md[:added_instance_methods]
      bits << "~#{md[:changed_instance_methods].size}im" if md[:changed_instance_methods]
      bits << "-#{md[:removed_instance_methods].size}im" if md[:removed_instance_methods]
      bits << "+#{md[:added_singleton_methods].size}sm" if md[:added_singleton_methods]
      bits << "~#{md[:changed_singleton_methods].size}sm" if md[:changed_singleton_methods]
      bits << "+#{md[:added_constants].size}c" if md[:added_constants]
      bits << "~#{md[:changed_constants].size}c" if md[:changed_constants]
      bits << "+#{md[:added_class_variables].size}cv" if md[:added_class_variables]
      bits << "+#{md[:added_class_ivars].size}civ" if md[:added_class_ivars]
      bits << "ANC" if md[:ancestors_changed]
      bits.join(" ")
    end

    # --- restore helpers ---

    def restore_module(mod, snap, errors)
      restore_methods(mod, snap, :instance, errors)
      restore_methods(mod, snap, :singleton, errors)
      restore_class_ivars(mod, snap, errors)
      restore_class_variables(mod, snap, errors)
      restore_constants(mod, snap, errors)
      restore_ancestors(mod, snap, errors)
    rescue StandardError => e
      errors << "#{format_mod(mod)}: #{e.class}: #{e.message}"
    end

    def restore_methods(mod, snap, kind, errors)
      now = capture_methods(mod, kind)
      snap_methods = (kind == :instance) ? snap[:instance_methods] : snap[:singleton_methods]
      owner = (kind == :instance) ? mod : mod.singleton_class

      # Methods added since capture: remove. This is the alias-chain fix —
      # e.g. PSDK's `:_original_initialize` gets stripped here.
      (now.keys - snap_methods.keys).each do |name|
        begin
          owner.send(:remove_method, name)
        rescue NameError => e
          errors << "remove #{format_mod(mod)}##{name} (#{kind}): #{e.message}"
        end
      end

      # Methods that exist both then and now but were redefined:
      # define_method with the captured UnboundMethod puts the original
      # iseq back in the method table. This is the *real* unwind of
      # `def initialize; _original_initialize; ...; end`.
      snap_methods.each do |name, unbound|
        next unless now.key?(name)
        next if now[name] == unbound
        begin
          owner.send(:define_method, name, unbound)
        rescue StandardError => e
          errors << "rebind #{format_mod(mod)}##{name} (#{kind}): #{e.class}: #{e.message}"
        end
      end

      # Methods removed since capture: rare (PSDK rarely undef_methods).
      # Restore them via the captured UnboundMethod.
      (snap_methods.keys - now.keys).each do |name|
        begin
          owner.send(:define_method, name, snap_methods[name])
        rescue TypeError => e
          # C-implemented methods carry an internal receiver-type check
          # (e.g. IO#nonblock? from io/wait), enforced even after stdlib
          # delegation re-exposes them on a non-subclass like Tempfile.
          # The UnboundMethod's owner reports as the host class, but
          # define_method still raises "bind argument must be a subclass
          # of IO". Nothing Ruby-level can put it back; the next require
          # of the originating stdlib file reinstalls it.
          logger.info("[PSDKVMSnapshot] skip readd #{format_mod(mod)}##{name} (#{kind}): #{e.message}")
        rescue StandardError => e
          errors << "readd #{format_mod(mod)}##{name} (#{kind}): #{e.class}: #{e.message}"
        end
      end
    end

    def restore_constants(mod, snap, errors)
      now = capture_constants(mod)

      # Added constants — remove. This is what reaps the PSDK-defined
      # classes (`Foo::Bar = Class.new`): once the parent stops pointing
      # at them, the orphaned class objects are GC-eligible.
      #
      # Exception: stdlib-defined Module/Class values (e.g. ::JSON,
      # ::OpenSSL — required mid-session) must NOT be removed. Their
      # backing $LOADED_FEATURES entries are also kept (see
      # restore_loaded_features), so next session's `require` is a
      # no-op; removing the constant would leave the require cache
      # claiming the lib is loaded while the lookup fails — exactly
      # the NameError on ::JSON that we hit in the wild.
      (now.keys - snap[:constants].keys).each do |name|
        value = now[name]
        next if from_stdlib_module?(value)
        begin
          mod.send(:remove_const, name) if mod.const_defined?(name, false)
        rescue NameError => e
          errors << "remove_const #{format_mod(mod)}::#{name}: #{e.message}"
        end
      end

      # Changed constants — re-set to the captured value.
      snap[:constants].each do |name, value|
        next if value.equal?(:__unreadable__)
        if now.key?(name)
          next if now[name].equal?(value)
          begin
            mod.send(:remove_const, name) if mod.const_defined?(name, false)
          rescue NameError
          end
        end
        begin
          mod.const_set(name, value)
        rescue StandardError => e
          errors << "set_const #{format_mod(mod)}::#{name}: #{e.class}: #{e.message}"
        end
      end
    end

    def restore_class_variables(mod, snap, errors)
      now_names = mod.class_variables
      (now_names - snap[:class_variables].keys).each do |name|
        begin
          mod.send(:remove_class_variable, name)
        rescue NameError => e
          errors << "remove_cv #{format_mod(mod)}.#{name}: #{e.message}"
        end
      end
      snap[:class_variables].each do |name, value|
        begin
          current = (mod.class_variable_defined?(name) ? mod.class_variable_get(name) : nil)
          next if current.equal?(value)
          mod.class_variable_set(name, value)
        rescue StandardError => e
          errors << "set_cv #{format_mod(mod)}.#{name}: #{e.class}: #{e.message}"
        end
      end
    end

    def restore_class_ivars(mod, snap, errors)
      now_names = mod.instance_variables
      (now_names - snap[:class_ivars].keys).each do |name|
        begin
          mod.send(:remove_instance_variable, name)
        rescue NameError
          # Already gone — fine.
        end
      end
      snap[:class_ivars].each do |name, value|
        current = mod.instance_variable_get(name)
        next if current.equal?(value)
        mod.instance_variable_set(name, value)
      end
    end

    # If the native extension is loaded, splice out the newly-prepended
    # /included IClasses by direct chain manipulation in C. If it isn't,
    # log the change so we know what the spike is missing.
    def restore_ancestors(mod, snap, errors)
      return if mod.ancestors == snap[:ancestors]
      added = mod.ancestors - snap[:ancestors]
      # `mod` itself appears in `ancestors` but never in `added` (it's
      # in both before and after) — defensive filter anyway.
      added.reject! { |a| a.equal?(mod) }
      return if added.empty?

      unless NATIVE_AVAILABLE
        errors << "ancestors of #{format_mod(mod)} changed " \
                  "(added: #{added.map { |a| format_mod(a) }.join(', ')}) " \
                  "— native ext not loaded, not undone"
        return
      end

      added.each do |ancestor|
        begin
          removed = PSDKVMSnapshot::Native.unprepend(mod, ancestor)
          unless removed
            # The ancestor was in mod.ancestors but not as a direct
            # IClass in mod's chain — likely inherited via a parent
            # class's prepend. We don't reach across the hierarchy
            # boundary; the parent class's own restore handles it.
            logger.info("[PSDKVMSnapshot] #{format_mod(mod)}: " \
                        "#{format_mod(ancestor)} not in own chain (inherited?), skipping")
          end
        rescue StandardError => e
          errors << "unprepend #{format_mod(mod)} from #{format_mod(ancestor)}: " \
                    "#{e.class}: #{e.message}"
        end
      end
    end

    def restore_loaded_features(errors)
      # Re-add features removed mid-session (rare — PSDK occasionally
      # $LOADED_FEATURES.delete to force a reload of one of its own files).
      current = $LOADED_FEATURES.dup
      (@snapshot[:loaded_features] - current).each { |f| $LOADED_FEATURES << f }

      # Remove features added mid-session — BUT skip anything under stdlib
      # or gem dirs. Forcing a re-require of stdlib (especially C extensions
      # like json/ext.so) breaks: the .so is already dlopen'd, Init_ can't
      # safely re-run, the require raises LoadError, the .rb fallback
      # (json/pure) isn't packaged, and the whole require chain dies.
      # Keeping these entries means next session's `require` is a no-op and
      # the corresponding top-level Module/Class constants (also kept by
      # restore_constants) remain reachable.
      (current - @snapshot[:loaded_features]).each do |f|
        is_stdlib = from_stdlib_path?(f)
        # TEMP DIAGNOSTIC: log every removal decision so we can see whether
        # the path matcher is misclassifying stdlib entries (e.g. json.rb).
        $stderr.puts("[PSDKVMSnapshot][DIAG] feature #{is_stdlib ? 'KEEP' : 'DROP'}: #{f}")
        next if is_stdlib
        $LOADED_FEATURES.delete(f)
      end
    rescue StandardError => e
      errors << "loaded_features: #{e.class}: #{e.message}"
    end

    # Restoring globals needs eval because there's no API to assign $-vars
    # by symbol. We build a one-shot lambda per name; the lambda body
    # contains the literal `$foo = v` so the parser binds it as a global
    # reference. Names that don't look like normal identifiers ($~, $`, $0…)
    # are skipped — they're either read-only or special-purpose.
    GLOBAL_NAME_RE = /\A\$[a-zA-Z_][a-zA-Z0-9_]*\z/

    def restore_globals(errors)
      current = capture_globals

      (current.keys - @snapshot[:global_vars].keys).each do |name|
        next unless GLOBAL_NAME_RE.match?(name.to_s)
        begin
          eval("->(v) { #{name} = v }").call(nil)
        rescue StandardError => e
          errors << "nil global #{name}: #{e.class}: #{e.message}"
        end
      end

      @snapshot[:global_vars].each do |name, value|
        next unless GLOBAL_NAME_RE.match?(name.to_s)
        next unless current.key?(name)
        next if current[name].equal?(value)
        begin
          eval("->(v) { #{name} = v }").call(value)
        rescue StandardError => e
          errors << "set global #{name}: #{e.class}: #{e.message}"
        end
      end
    end

    def format_mod(mod)
      mod.name || "(anon ##{mod.object_id})"
    end

    # True for any path that lives under Ruby's stdlib or gem install dirs.
    # Used to skip removal of stdlib state during restore — see the comment
    # on STDLIB_PATH_PREFIXES above the module definition.
    #
    # Path shapes we have to handle:
    #   /data/.../json.rb            — absolute path → prefix-match
    #   json/ext/parser.so           — relative; stdlib C ext registration
    #   <internal:/.../kernel_require.rb> — Ruby's wrapper for core files;
    #                                  unwrap and prefix-match the inner path
    #   <socket-script-1>, <main>    — user script markers; NEVER stdlib
    #
    # The trailing-separator guard on the prefix match prevents prefix-
    # stealing collisions (/foo/3.1.0/x.rb falsely matching /foo/3.1).
    def from_stdlib_path?(path)
      return false unless path.is_a?(String)

      if path.start_with?('<internal:') && path.end_with?('>')
        inner = path[10..-2]
        return STDLIB_PATH_PREFIXES.any? do |prefix|
          inner == prefix || inner.start_with?(prefix + '/')
        end
      end

      return false if path.start_with?('<')
      return true unless path.start_with?('/')

      STDLIB_PATH_PREFIXES.any? do |prefix|
        path == prefix || path.start_with?(prefix + '/')
      end
    end

    # True if `mod` was defined by stdlib. Heuristic uses two signals:
    #
    # 1. Method source_location — covers any module with Ruby-defined
    #    methods (e.g. JSON.parse defined in stdlib's json/common.rb).
    #
    # 2. Constant source_location (Module#const_source_location, Ruby
    #    2.7+) — covers pure-namespace modules with no methods of their
    #    own but whose nested constants are defined in stdlib files
    #    (e.g. some C-extension-backed namespaces).
    #
    # Both probes return `[path, line]`, and we check the path against
    # the stdlib prefix list. We deliberately do NOT fall back to
    # file-basename matching: that misclassifies app namespaces whose
    # short name happens to collide with a stdlib filename (e.g. the
    # in-tree `Loader` module vs rubygems' `loader.rb`), which causes
    # restore to skip removal of app code and produces a flood of
    # "already initialized constant" warnings on re-eval.
    def from_stdlib_module?(mod)
      return false unless mod.is_a?(Module)

      (mod.instance_methods(false) + mod.singleton_methods(false)).first(10).each do |name|
        loc = (mod.instance_method(name) rescue mod.method(name) rescue nil)&.source_location
        return true if loc && from_stdlib_path?(loc[0])
      end

      if mod.respond_to?(:const_source_location)
        mod.constants(false).first(10).each do |name|
          loc = (mod.const_source_location(name, false) rescue nil)
          return true if loc && from_stdlib_path?(loc[0])
        end
      end

      false
    end

    def log_overflow(total, cap)
      return unless total > cap
      logger.info("[PSDKVMSnapshot]   ... (#{total - cap} more)")
    end
  end
end
end # unless defined?(PSDKVMSnapshot)

# Polyfill: Array#to_set_compat without requiring 'set' (avoids touching
# $LOADED_FEATURES in environments where we care about it staying clean).
class Array
  def to_set_compat
    h = {}
    each { |x| h[x] = true }
    SnapshotMembership.new(h)
  end unless method_defined?(:to_set_compat)
end

class SnapshotMembership
  def initialize(hash); @h = hash; end
  def include?(key); @h.key?(key); end
end

# Auto-capture at file-load time so the baseline freezes BEFORE any
# downstream prelude (loader/util.rb etc.) defines its constants. Without
# this, those constants land in the baseline; restore! preserves them;
# the next session re-evals the same file and Ruby warns "already
# initialized constant ..." for every one. capture! is idempotent —
# explicit downstream calls (start.rb, loader.rb) become no-ops, kept
# only as lifecycle documentation at the call site.
PSDKVMSnapshot.capture!
