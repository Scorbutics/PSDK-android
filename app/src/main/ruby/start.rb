begin
  Dir.chdir './Release'
  ARGV << 'verbose'
  ARGV << "--scale=4"

  ENV['PSDK_SHADER_IMPL'] = 'glsl_es'
  ENV['PSDK_SHADER_VERSION'] = ''

  require './Game.rb'
rescue Exception => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end