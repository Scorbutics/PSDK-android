begin
  Dir.chdir './Release'
  ARGV << 'verbose'
  ARGV << 'fullscreen'
  ARGV << "--scale=4"

  ENV['PSDK_SHADER_IMPL'] = ''
  ENV['PSDK_SHADER_VERSION'] = ''

  require './Game.rb'
rescue Exception => error
  STDOUT.flush
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end