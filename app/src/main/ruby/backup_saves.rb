require 'fileutils'

RELEASE_DIR = './Release'
BACKUP_DIR = './ReleaseBackup'

# Clean any stale backup from a previous failed attempt
FileUtils.rm_rf(BACKUP_DIR) if File.exist?(BACKUP_DIR)

if File.exist?(RELEASE_DIR) && File.directory?(RELEASE_DIR)
  File.rename(RELEASE_DIR, BACKUP_DIR)
  STDERR.puts "Renamed #{RELEASE_DIR} to #{BACKUP_DIR}"
  puts "Release folder backed up successfully."
else
  STDERR.puts "No existing Release folder, nothing to back up"
  puts "No previous Release to back up."
end
