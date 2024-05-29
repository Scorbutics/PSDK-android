require 'fileutils'
FileUtils.rm_rf('./Release');
if File.exist?("Release")
    raise "Unable to remove existing compiled data"
end

File.open('.gameopts', 'w') { |file| file.write("--util=project_compilation") }
ARGV << "skip_lib"
ARGV << "skip_binary"

FileUtils.mkdir_p "./Data/Events/Battle"

require 'LiteRGSS'
LiteRGSS::Shader.available = false
require 'ruby_physfs_patch.rb'
require './Game.rb'
