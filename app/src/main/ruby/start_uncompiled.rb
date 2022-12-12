begin
  Dir.chdir ENV["PSDK_ANDROID_FOLDER_LOCATION"]
  puts "Going to directory : " + Dir.pwd

  ENV['PSDK_BINARY_PATH'] = ""
  File.open('.gameopts', 'w').close()

  require 'ruby_physfs_patch.rb'
  require './Game.rb'
rescue => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end