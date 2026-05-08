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
STDERR.puts "Archive location: " + ENV.fetch('PSDK_EPSA_PATH', '<unset>')

require 'LiteRGSS'
LiteRGSS::Shader.available = false

# Mount the encrypted .epsa archive via a streaming decrypter (EpsaStream),
# then point writes to the cwd (real filesystem). write_dir is mounted at
# priority 0 so any save written here shadows the corresponding stock copy
# in the archive.
#
# The first mount auto-activates the C-extension shim that re-routes
# File / Dir / IO / Kernel#require through the VFS — pokemonsdk code can
# keep using stdlib calls unchanged. Last unmount auto-deactivates it.
#
# `physfs` is statically linked into libembedded-ruby-vm and pre-provided
# at VM startup by extension-init.c (it calls Init_physfs + rb_provide),
# so this `require` is a no-op kept for explicitness. EpsaStream and
# ArchiveMount are prepended to this script as preludes by RubyScript at
# compile time — see CompilationEngine.ARCHIVE_PRELUDES.
require 'physfs'
ArchiveMount.mount!
PhysFS.write_dir = Dir.pwd.end_with?('/') ? Dir.pwd : "#{Dir.pwd}/"

require './Game.rb'
