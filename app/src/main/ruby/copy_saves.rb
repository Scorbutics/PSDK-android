require 'ruby_physfs_patch.rb'
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
if File.OldDirectory?(BACKUP_SAVES)
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
if File.OldDirectory?(BACKUP_DIR)
  FileUtils.rm_rf(BACKUP_DIR)
  STDERR.puts "Release backup cleaned up."
end
