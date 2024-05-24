begin
  File.open('.gameopts', 'w').close()
  require 'ruby_physfs_patch.rb'
  require './Game.rb'
rescue Exception => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end