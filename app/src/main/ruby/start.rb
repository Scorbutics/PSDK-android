require 'safe_runner'

# Debug-build convenience: mirror desktop PSDK's `S.MI` shorthand for the
# current MapInterpreter instance so the same expression works in the remote
# eval console here on Android. PSDK Android doesn't define `S` itself
# (verified live — `NameError: uninitialized constant S; Did you mean? Sf`),
# so we install a tiny debug module under that name. The method body is
# lazy: it only references `Interpreter` when called, so this works even
# though Interpreter isn't yet defined when start.rb runs.
#
# Gated on RGSS_REMOTE_EVAL_TOKEN — that env var is only set in debug builds
# (see GameLauncher#publishRemoteListenerEnv), so release builds skip this
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

  ARGV << 'verbose'
  ARGV << 'fullscreen'
  ARGV << "--scale=4"

  ENV['PSDK_SHADER_IMPL'] = ''
  ENV['PSDK_SHADER_VERSION'] = ''

  puts "Platform: #{RUBY_PLATFORM}"
  puts "Ruby version: #{RUBY_VERSION}"

  VMLogger.info "[start.rb] Loading Game.rb..."
  require './Game.rb'
  VMLogger.info "[start.rb] Game.rb finished normally"
end
