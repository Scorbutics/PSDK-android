require 'fileutils'

# Self-guard: backup_saves.rb and copy_saves.rb both run on the same reused
# compile VM and both assign these top-level constants. Without the guard,
# whichever script runs second emits "already initialized constant" warnings.
# Same value in both files, so the guard is content-safe.
RELEASE_DIR = './Release'        unless defined?(RELEASE_DIR)
BACKUP_DIR  = './ReleaseBackup'  unless defined?(BACKUP_DIR)

if File.directory?(BACKUP_DIR)
  # A previous compilation was interrupted (process killed) after the backup step: the backup
  # still holds the user saves, while Release is a partial build. Keep the backup, drop Release.
  # CompilationEngine.recoverInterruptedCompilation normally restores it before we get here.
  FileUtils.rm_rf(RELEASE_DIR)
  STDERR.puts "Kept #{BACKUP_DIR} from an interrupted compilation, removed the partial #{RELEASE_DIR}"
  puts "Previous Release backup kept (interrupted compilation)."
elsif File.directory?(RELEASE_DIR)
  File.rename(RELEASE_DIR, BACKUP_DIR)
  STDERR.puts "Renamed #{RELEASE_DIR} to #{BACKUP_DIR}"
  puts "Release folder backed up successfully."
else
  STDERR.puts "No existing Release folder, nothing to back up"
  puts "No previous Release to back up."
end
