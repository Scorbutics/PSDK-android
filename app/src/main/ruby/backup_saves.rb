require 'fileutils'

SAVES_DIR = './Release/Saves'
BACKUP_DIR = './SavesBackup'

# Clean any stale backup from a previous failed attempt
FileUtils.rm_rf(BACKUP_DIR) if File.exist?(BACKUP_DIR)

if File.exist?(SAVES_DIR) && File.directory?(SAVES_DIR)
  entries = Dir.entries(SAVES_DIR).reject { |e| e == '.' || e == '..' }
  if entries.any?
    FileUtils.cp_r(SAVES_DIR, BACKUP_DIR)
    STDERR.puts "Backed up #{entries.length} save entries to #{BACKUP_DIR}"
    puts "Saves backed up successfully."
  else
    STDERR.puts "Saves directory is empty, nothing to back up"
    puts "No saves to back up."
  end
else
  STDERR.puts "No saves directory found, nothing to back up"
  puts "No saves to back up."
end
