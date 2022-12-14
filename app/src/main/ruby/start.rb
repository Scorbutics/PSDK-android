begin
  Dir.chdir './Release'
  require './Game.rb'
rescue => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end