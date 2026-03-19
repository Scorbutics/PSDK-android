require 'safe_runner'

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
