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

module PSDKVMSnapshot
  class StderrLogger
    def info(msg); $stderr.puts("[I] #{msg}"); end
    def error(msg); $stderr.puts("[E] #{msg}"); end
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

    # Stage-2 work. For now: refuse silently if not captured, otherwise log.
    def restore!
      unless captured?
        logger.info("[PSDKVMSnapshot] restore! called before capture!; no-op")
        return self
      end
      logger.info("[PSDKVMSnapshot] restore! is a stub (Stage 1). No-op.")
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

    def format_mod(mod)
      mod.name || "(anon ##{mod.object_id})"
    end

    def log_overflow(total, cap)
      return unless total > cap
      logger.info("[PSDKVMSnapshot]   ... (#{total - cap} more)")
    end
  end
end

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
