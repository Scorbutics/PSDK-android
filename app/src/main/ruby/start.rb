begin
  Dir.chdir ENV["PSDK_ANDROID_FOLDER_LOCATION"] + '/Release'
  ENV['PSDK_BINARY_PATH'] = ""
  require './Game.rb'
rescue => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end