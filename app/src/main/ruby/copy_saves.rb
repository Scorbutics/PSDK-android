# Mount the APK assets archive at PhysFS root, and point writes to the cwd
# (real filesystem). The first mount auto-activates the C-extension shim
# that re-routes File / Dir / IO / Kernel#require through the VFS — saves
# written to write_dir shadow the archive's stock copy on subsequent reads.
# `physfs` is statically linked into libembedded-ruby-vm and pre-provided
# at VM startup, so the require is a no-op kept for explicitness.
require 'physfs'
PhysFS.mount ENV['PSDK_ANDROID_ADDITIONAL_PARAM']
PhysFS.write_dir = Dir.pwd.end_with?('/') ? Dir.pwd : "#{Dir.pwd}/"

require 'fileutils'

unless File.exist?("Release")
    raise "Unable to find a valid release folder"
end

def copy_directory_recursive(src, dest)
  # Create the destination directory if it doesn't exist
  Dir.mkdir(dest) unless Dir.exist?(dest)

  Dir.entries(src).each do |entry|
    next if entry == '.' || entry == '..'
    src_path = File.join(src, entry)
    dest_path = File.join(dest, entry)

    if File.directory?(src_path)
      copy_directory_recursive(src_path, dest_path)
    else
      STDERR.puts "Copying #{src_path} to #{dest_path}"
      IO.copy_stream(src_path, dest_path)
    end
  end
end

STDERR.puts "Archive location: " + ENV["PSDK_ANDROID_ADDITIONAL_PARAM"]

BACKUP_DIR = './ReleaseBackup'
BACKUP_SAVES = File.join(BACKUP_DIR, 'Saves')
DEST_SAVES = File.join('Release', 'Saves')

# User saves from previous installation take priority over bundled archive saves
restored_from_backup = false
if File.directory?(BACKUP_SAVES)
  entries = Dir.entries(BACKUP_SAVES).reject { |e| e == '.' || e == '..' }
  if entries.any?
    copy_directory_recursive(BACKUP_SAVES, DEST_SAVES)
    puts "User saves restored from previous Release."
    restored_from_backup = true
  end
end

if !restored_from_backup
  if File.exist?('Saves')
    copy_directory_recursive('Saves', DEST_SAVES)
    puts "Saves were copied with success in the release folder."
  else
    puts "No saves included in this compilation."
  end
end

# Clean up backup now that compilation succeeded
if File.directory?(BACKUP_DIR)
  FileUtils.rm_rf(BACKUP_DIR)
  STDERR.puts "Release backup cleaned up."
end
