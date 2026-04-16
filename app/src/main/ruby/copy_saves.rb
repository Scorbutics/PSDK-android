require 'ruby_physfs_patch.rb'

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

if File.exist?('Saves')
    copy_directory_recursive('Saves', File.join('Release', 'Saves'))
    puts "Saves were copied with success in the release folder."
else
    puts "No saves included in this compilation."
end

# Restore backed-up user saves (from previous installation)
# User saves take priority over bundled archive saves
BACKUP_DIR = './SavesBackup'
DEST_SAVES = File.join('Release', 'Saves')

if File.OldDirectory?(BACKUP_DIR)
  Dir.mkdir(DEST_SAVES) unless File.OldDirectory?(DEST_SAVES)

  Dir.entries(BACKUP_DIR).each do |entry|
    next if entry == '.' || entry == '..'
    src_path = File.join(BACKUP_DIR, entry)
    dest_path = File.join(DEST_SAVES, entry)

    if File.OldDirectory?(src_path)
      copy_directory_recursive(src_path, dest_path)
    else
      STDERR.puts "Restoring save #{src_path} to #{dest_path}"
      IO.copy_stream(src_path, dest_path)
    end
  end
  puts "User saves restored from backup."

  # Clean up backup
  require 'fileutils'
  FileUtils.rm_rf(BACKUP_DIR)
  STDERR.puts "Backup directory cleaned up."
else
  puts "No save backup found to restore."
end
