require 'fileutils'
FileUtils.rm_rf('./Release');
if File.exist?("Release")
    raise "Unable to remove existing compiled data"
end

File.open('.gameopts', 'w') { |file| file.write("--util=project_compilation") }
ARGV << "skip_lib"
ARGV << "skip_binary"

FileUtils.mkdir_p "./Data/Events/Battle"

STDERR.puts "Current directory: #{Dir.pwd}"
STDERR.puts "Archive location: " + ENV["PSDK_ANDROID_ADDITIONAL_PARAM"]

require 'LiteRGSS'
LiteRGSS::Shader.available = false

# Mount the APK assets archive at PhysFS root, and point writes to the cwd
# (real filesystem). write_dir is mounted at priority 0 so any save written
# here shadows the corresponding stock copy in the archive.
#
# The first mount auto-activates the C-extension shim that re-routes
# File / Dir / IO / Kernel#require through the VFS — pokemonsdk code can
# keep using stdlib calls unchanged. Last unmount auto-deactivates it.
#
# `physfs` is statically linked into libembedded-ruby-vm and pre-provided
# at VM startup by extension-init.c (it calls Init_physfs + rb_provide),
# so this `require` is a no-op kept for explicitness.
require 'physfs'
PhysFS.mount ENV['PSDK_ANDROID_ADDITIONAL_PARAM']
PhysFS.write_dir = Dir.pwd.end_with?('/') ? Dir.pwd : "#{Dir.pwd}/"

require './Game.rb'
