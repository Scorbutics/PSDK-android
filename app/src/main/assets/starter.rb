begin
  require 'rubygems'
  Dir.chdir ENV["PSDK_ANDROID_FOLDER_LOCATION"]
  require './Game.rb'
rescue => error
  puts error
end

