require 'safe_runner'
# start.rb runs once per process: GameActivity lives in its own `:game`
# Android process and kills it when the game exits, so every launch gets
# a fresh Ruby VM. Nothing here has to be idempotent across launches.

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
# (see GameActivity#configureGameEnv), so release builds skip this
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
    # Stale --util payload left on disk by a compile run would silently
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
  # Implemented as a marker file written from inside a `.new` wrapper on
  # LiteRGSS::DisplayWindow. The file path is the env var
  # RGSS_GAME_READY_FILE that GameActivity sets on workload launch;
  # GameActivity polls for it via Handler and fades the overlay out as
  # soon as it appears.
  #
  # Why a file and not a log sentinel: the VMLogger/STDOUT pipeline
  # delivers reliably for the loader workload but is silently dropped
  # for the game workload on this host (observed: C-side LiteRGSS
  # warnings reach the LogListener but Ruby-side puts/VMLogger.info do
  # not). File-based sync sidesteps that entirely — and the
  # mechanism is already proven by the wizard ↔ loader status file.
  #
  # Aliasing `.new` on the singleton class (rather than prepending on
  # `initialize`) is dispatch-agnostic: `.new` is the public entry
  # point regardless of how the C side wires up internals, so it works
  # even if the litergss2 C code ever takes a `rb_class_new_instance`
  # shortcut around Ruby-level method lookup.
  ready_file_path = ENV['RGSS_GAME_READY_FILE']
  if ready_file_path.nil? || ready_file_path.empty?
    STDERR.puts '[start.rb] RGSS_GAME_READY_FILE not set; sentinel skipped'
  elsif !defined?(LiteRGSS::DisplayWindow)
    STDERR.puts '[start.rb] LiteRGSS::DisplayWindow not defined; sentinel skipped'
  else
    # Stale file from a prior session would tell the host the game is
    # ready before this session has constructed anything. Wipe it before
    # installing the wrapper.
    File.delete(ready_file_path) rescue nil

    LiteRGSS::DisplayWindow.class_variable_set(:@@__game_display_signaled, false)
    LiteRGSS::DisplayWindow.class_variable_set(:@@__game_display_ready_path, ready_file_path)
    LiteRGSS::DisplayWindow.singleton_class.send(:alias_method, :__game_display_orig_new, :new)
    LiteRGSS::DisplayWindow.singleton_class.send(:define_method, :new) do |*args, &block|
      instance = __game_display_orig_new(*args, &block)
      unless class_variable_get(:@@__game_display_signaled)
        class_variable_set(:@@__game_display_signaled, true)
        path = class_variable_get(:@@__game_display_ready_path)
        begin
          File.write(path, "ready\n")
        rescue StandardError => e
          STDERR.puts "[start.rb] failed to write ready file #{path}: #{e.class}: #{e.message}"
        end
      end
      instance
    end
  end

  # exit! must end the game. SafeRunner turns exit/exit! into a SystemExit so
  # a script can't kill the host process, but that makes exit! rescuable,
  # and PSDK's `rescue Exception` handlers caught it and kept the game
  # running. On the game thread, exit! now throws to the catch around
  # Game.rb below: a throw unwinds (running ensure blocks) but no rescue can
  # stop it, so Game.rb returns and GameActivity closes like a normal end.
  # Other threads keep SafeRunner's behaviour. This file only runs in the
  # `:game` process, never on the shell's loader/compile VM.
  game_thread = Thread.current
  Kernel.module_eval do
    alias_method :__start_rb_safe_exit!, :exit! unless method_defined?(:__start_rb_safe_exit!, false) || private_method_defined?(:__start_rb_safe_exit!, false)
    define_method(:exit!) do |status = nil|
      return __start_rb_safe_exit!(status.nil? ? false : status) unless Thread.current == game_thread

      # A bare exit! (Ruby's default status is false, i.e. 1) is how games
      # quit: report it as a normal end, not as an error. Only an explicit
      # failure status is passed on as the exit code.
      code = status.is_a?(Integer) ? status : (status == false ? 1 : 0)
      VMLogger.info "[start.rb] exit!(#{code}) called: ending the game"
      $__safe_runner_exit_code = code unless code.zero?
      throw :__psdk_game_exit
    end
    private :exit!
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
    catch(:__psdk_game_exit) { require './Game.rb' }
    VMLogger.info "[start.rb] Game.rb finished normally"
  ensure
    $VERBOSE = old_verbose
    if File.exist?(error_log) && File.mtime(error_log) != error_log_mtime_before
      VMLogger.error "[start.rb] --- Error.log contents (#{error_log}) ---"
      File.read(error_log).each_line { |line| VMLogger.error line.chomp }
      VMLogger.error "[start.rb] --- end of Error.log ---"
    end
    # Symmetric with extract_loader_assets.rb so PhysFS closes the archive
    # cleanly before the process is torn down. Noop in release mode.
    if defined?(ArchiveMount)
      ArchiveMount.unmount! rescue nil
    end
  end
end
