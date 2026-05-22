require 'fileutils'

# Self-guard: backup_saves.rb and copy_saves.rb both run on the same reused
# compile VM and both assign these top-level constants. Without the guard,
# whichever script runs second emits "already initialized constant" warnings.
# Same value in both files, so the guard is content-safe.
RELEASE_DIR = './Release'        unless defined?(RELEASE_DIR)
BACKUP_DIR  = './ReleaseBackup'  unless defined?(BACKUP_DIR)

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
