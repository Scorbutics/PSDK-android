require 'safe_runner'
# psdk_vm_snapshot.rb is concatenated as a prelude by RootActivity (see
# START_PRELUDES) — same pattern as the loader's LOADER_PRELUDES.
# The Android embedded VM `eval`s start.rb as a string with __FILE__ set
# to "<socket-script-N>", so require_relative can't resolve it.

# PSDK's compiled-mode console (pokemonsdk/scripts/tools/GameLoader/
# 32_console_compiled.rb) reopens Kernel and redefines log_error /
# log_info / log_debug as NO-OPS that just `return message`. That
# silences every shader-compile failure, missing-asset error, and
# PSDK-internal diagnostic in release builds — which is why
# adb logcat shows zero shader errors even when shaders visibly
# fail (e.g. title_button background renders as a blank rect).
#
# Re-route through VMLogger so the diagnostics surface in logcat
# regardless of compiled vs uncompiled mode. Using Module#prepend
# (not a plain reopen) so this layer sits BEFORE Kernel in the
# method-resolution chain — PSDK's `module Kernel; def log_error;
# ...; end; end` reopens Kernel itself, which lives after our
# prepended module, so our wrapper is hit first and `super` falls
# through to PSDK's no-op (which is fine — we already logged).
#
# Falls back to defining a no-op log_error on Kernel first, in case
# our require is called before PSDK has had a chance to install
# its own (`super` would NoMethodError otherwise).
module Kernel
  unless method_defined?(:log_error) || private_method_defined?(:log_error)
    def log_error(message); message; end
  end
  unless method_defined?(:log_info)  || private_method_defined?(:log_info)
    def log_info(message);  message; end
  end
  unless method_defined?(:log_debug) || private_method_defined?(:log_debug)
    def log_debug(message); message; end
  end
end

module PsdkLogTee
  def log_error(message)
    VMLogger.error("[PSDK] #{message}") if defined?(VMLogger)
    super
  end
  def log_info(message)
    VMLogger.info("[PSDK] #{message}") if defined?(VMLogger)
    super
  end
  def log_debug(message)
    VMLogger.info("[PSDK] #{message}") if defined?(VMLogger)
    super
  end
end
Kernel.prepend(PsdkLogTee)
VMLogger.info "[start.rb] PsdkLogTee installed — PSDK log_error/info/debug routed to VMLogger"

# Debug-build convenience: mirror desktop PSDK's `S.MI` shorthand for the
# current MapInterpreter instance so the same expression works in the remote
# eval console here on Android. PSDK Android doesn't define `S` itself
# (verified live — `NameError: uninitialized constant S; Did you mean? Sf`),
# so we install a tiny debug module under that name. The method body is
# lazy: it only references `Interpreter` when called, so this works even
# though Interpreter isn't yet defined when start.rb runs.
#
# Gated on RGSS_REMOTE_EVAL_TOKEN — that env var is only set in debug builds
# (see RootActivity#configureGameEnv), so release builds skip this
# entirely and never get an `S` constant they didn't ask for.
if ENV['RGSS_REMOTE_EVAL_TOKEN'] && !ENV['RGSS_REMOTE_EVAL_TOKEN'].to_s.empty?
  unless defined?(S)
    module ::S
      def self.MI
        ObjectSpace.each_object(Interpreter).to_a.first
      end
    end
    VMLogger.info "[start.rb] S.MI debug shortcut installed"
  end
end

SafeRunner.run(
  log_path: ENV['RGSS_LOG_FILE'],
  error_log_path: ENV['RGSS_ERROR_LOG_FILE']
) do
  execution_location = ENV['RGSS_EXECUTION_LOCATION']

  if execution_location && !execution_location.empty?
    release_dir = File.join(execution_location, 'Release')
    VMLogger.info "[start.rb] Changing to release directory: #{release_dir}"
    Dir.chdir(release_dir)
  else
    VMLogger.info "[start.rb] No execution location set, using relative path"
    Dir.chdir './Release'
  end

  VMLogger.info "[start.rb] Current directory after chdir: #{Dir.pwd}"

  # DIAG: log whether PhysFS is still mounted from a prior workload.
  # In the open-app → start-game path nothing should have mounted the
  # archive, so this should print "PhysFS not mounted". If it prints
  # mounted=true, some earlier workload (copy_saves.rb / extract_loader_assets.rb
  # / compile.rb's check_engine.rb prelude) leaked a mount and File.read /
  # Dir.glob during the game session are silently routing through the
  # PhysFS shim, which could surface as shader files read from the
  # decrypted-stream pipeline instead of disk.
  if defined?(PhysFS) && defined?(ArchiveMount)
    VMLogger.info "[start.rb][DIAG] PhysFS mounted? #{ArchiveMount.mounted? rescue 'probe failed'}"
  else
    VMLogger.info "[start.rb][DIAG] PhysFS / ArchiveMount module not loaded — definitely not mounted"
  end

  ARGV << 'verbose'
  ARGV << 'fullscreen'
  ARGV << "--scale=4"

  ENV['PSDK_SHADER_IMPL'] = ''
  ENV['PSDK_SHADER_VERSION'] = ''

  puts "Platform: #{RUBY_PLATFORM}"
  puts "Ruby version: #{RUBY_VERSION}"

  # PSDK installs its own top-level rescue that writes Error.log and returns
  # normally — so Game.rb finishing "successfully" doesn't mean nothing crashed.
  # Snapshot Error.log's mtime before running and, in ensure, surface its
  # contents if it appeared or was touched during this run.
  error_log = File.join(Dir.pwd, 'Error.log')
  error_log_mtime_before = File.exist?(error_log) ? File.mtime(error_log) : nil

  # Spike Stage 1: take a baseline of the VM state right before Game.rb
  # runs. Idempotent across sessions — the first call wins, subsequent
  # game launches diff against the same baseline. log_diff! in the
  # ensure block prints what PSDK mutated, to guide Stage 2 (restore!).
  PSDKVMSnapshot.capture!

  VMLogger.info "[start.rb] Loading Game.rb..."
  # PSDK's project scripts reopen Interpreter and reassign in-game trainer /
  # event constants on every run; Ruby would otherwise spam "already
  # initialized constant" warnings to stderr for every one. $VERBOSE=nil
  # silences those warnings for the require block only and is restored on
  # the way out, even if require raises. Same pattern compile.rb uses.
  old_verbose = $VERBOSE
  $VERBOSE = nil
  begin
    require './Game.rb'
    VMLogger.info "[start.rb] Game.rb finished normally"
  ensure
    $VERBOSE = old_verbose
    if File.exist?(error_log) && File.mtime(error_log) != error_log_mtime_before
      VMLogger.error "[start.rb] --- Error.log contents (#{error_log}) ---"
      File.read(error_log).each_line { |line| VMLogger.error line.chomp }
      VMLogger.error "[start.rb] --- end of Error.log ---"
    end
    # Spike Stage 2: log what changed (diagnostic), then restore the VM
    # to the captured baseline so the next game launch on this same VM
    # starts from clean state. Without this, PSDK's alias_method chains
    # would compound across launches and trigger SystemStackError on the
    # second run (the original bug this spike exists to fix).
    PSDKVMSnapshot.log_diff!
    PSDKVMSnapshot.restore!
  end
end
