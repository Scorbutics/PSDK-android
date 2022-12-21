begin
  Dir.chdir './Release'
  ARGV << 'verbose'
  require './Game.rb'
rescue => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end