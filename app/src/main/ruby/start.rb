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
  game_mode          = ENV.fetch('RGSS_GAME_MODE', 'release')

  # Mode dispatch:
  #   release          → chdir into <exec>/Release; require './Game.rb' from
  #                      the real FS. Same path as before this branch existed.
  #   debug_uncompiled → chdir to <exec>; ArchiveMount.mount! the .epsa so
  #                      PSDK's File/Dir reads route through the PhysFS shim,
  #                      and `require './Game.rb'` resolves to the archive
  #                      copy. PSDK is told to skip its in-game compilation
  #                      pass (skip_lib, skip_binary): the .epsa already ships
  #                      precompiled .dat files, so there's nothing to rebuild.
  if game_mode == 'debug_uncompiled'
    if execution_location.nil? || execution_location.empty?
      raise "[start.rb] debug_uncompiled mode requires RGSS_EXECUTION_LOCATION"
    end
    VMLogger.info "[start.rb] Changing to debug uncompiled directory: #{execution_location}"
    Dir.chdir(execution_location)
  elsif execution_location && !execution_location.empty?
    release_dir = File.join(execution_location, 'Release')
    VMLogger.info "[start.rb] Changing to release directory: #{release_dir}"
    Dir.chdir(release_dir)
  else
    VMLogger.info "[start.rb] No execution location set, using relative path"
    Dir.chdir './Release'
  end

  VMLogger.info "[start.rb] Mode: #{game_mode}; cwd: #{Dir.pwd}"

  # DIAG: log whether PhysFS is still mounted from a prior workload.
  # In the release path nothing on the persistent VM should have mounted
  # the archive — if this prints mounted=true on release we leaked a mount
  # somewhere. In debug_uncompiled the mount happens below, AFTER this
  # diagnostic, so it will (correctly) still report unmounted here.
  if defined?(PhysFS) && defined?(ArchiveMount)
    VMLogger.info "[start.rb][DIAG] PhysFS mounted on entry? #{ArchiveMount.mounted? rescue 'probe failed'}"
  else
    VMLogger.info "[start.rb][DIAG] PhysFS / ArchiveMount module not loaded — definitely not mounted"
  end

  # Spike Stage 1: take a baseline of the VM state right before Game.rb
  # runs. Idempotent across sessions — the first call wins, subsequent
  # game launches diff against the same baseline. log_diff! in the
  # ensure block prints what PSDK mutated, to guide Stage 2 (restore!).
  # In practice the first call already happened at the top of the prelude
  # (psdk_vm_snapshot.rb's auto-capture); this is a no-op + lifecycle doc.
  PSDKVMSnapshot.capture!

  if game_mode == 'debug_uncompiled'
    # PSDK writes saves / project.studio / .gameopts through PhysFS.write_dir
    # onto the real FS. Anchor that at <exec> so saves end up in
    # <exec>/Saves/ (separate from <exec>/Release/Saves used by release mode
    # — keeps a debug-run bug from corrupting a release save).
    PhysFS.write_dir = Dir.pwd.end_with?('/') ? Dir.pwd : "#{Dir.pwd}/"
    ArchiveMount.mount!
    raise "[start.rb] debug_uncompiled: archive mount failed" unless ArchiveMount.mounted?

    # Markers PSDK reads at boot. Same scaffolding compile.rb sets up — without
    # them PSDK's data-text loader raises and KeyBinding.load_inputs tries to
    # write into a missing dir.
    studio_marker = File.join(PhysFS.write_dir, 'project.studio')
    File.write(studio_marker, '') unless File.exist?(studio_marker)
    saves_dir = File.join(PhysFS.write_dir, 'Saves')
    Dir.mkdir(saves_dir) rescue Errno::EEXIST
    input_path = File.join(saves_dir, 'input.json')
    File.write(input_path, '{}') unless File.exist?(input_path)
    # Stale --util payload from a prior compile run on this VM would silently
    # redirect Game.rb. Clear it.
    File.delete(File.join(PhysFS.write_dir, '.gameopts')) rescue nil

    # Tell PSDK to boot straight into the game: skip the in-engine script /
    # binary recompile pass. The archive already ships precompiled .dat files.
    ARGV << 'skip_lib' unless ARGV.include?('skip_lib')
    ARGV << 'skip_binary' unless ARGV.include?('skip_binary')
  end

  ARGV << 'verbose'    unless ARGV.include?('verbose')
  ARGV << 'fullscreen' unless ARGV.include?('fullscreen')
  ARGV << '--scale=4'  unless ARGV.include?('--scale=4')

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

  # One-shot signal to the Android host: PSDK has constructed its main
  # window, so the Kotlin-side "starting…" overlay can fade out and
  # reveal Game.rb's first frame.
  #
  # Installed via Module#prepend on LiteRGSS::DisplayWindow rather than
  # by hooking PSDK directly — PSDK is end-user code we don't own, but
  # the LiteRGSS API is stable and PSDK necessarily constructs exactly
  # one DisplayWindow on boot (its Graphics module). The prepend wraps
  # `initialize`, calls super first (so a constructor failure does NOT
  # mis-fire the sentinel), then emits on first successful return.
  #
  # The @@signaled class variable + the prepend module itself live above
  # the PSDKVMSnapshot baseline, so restore! reaps both at the end of
  # this session. Next session starts with no prepend and @@signaled
  # back to false — re-prepended cleanly on the next run.
  #
  # `ancestors.include?` guard is defensive against a restore that
  # somehow leaves the IClass in the chain (e.g., a native unprepend
  # failure logged but tolerated). Without it we'd double-prepend and
  # emit twice — harmless but noisy.
  unless LiteRGSS::DisplayWindow.ancestors.any? { |a| a.name == 'GameDisplayReadySignal' }
    module GameDisplayReadySignal
      @@signaled = false
      def initialize(*args, **kwargs)
        super
        unless @@signaled
          @@signaled = true
          VMLogger.info '[game-display-ready]' if defined?(VMLogger)
        end
      end
    end
    LiteRGSS::DisplayWindow.prepend(GameDisplayReadySignal)
  end

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
    # Unmount BEFORE PSDKVMSnapshot.restore! — restore manipulates the
    # filesystem-shim state (PhysFS modules, $LOADED_FEATURES entries) and we
    # don't want it doing that while a mount is live. Symmetric with
    # extract_loader_assets.rb. Idempotent: noop in release mode and on retry.
    if defined?(ArchiveMount)
      ArchiveMount.unmount! rescue nil
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
