# Mount the encrypted .epsa archive via a streaming decrypter (EpsaStream),
# then point writes to the cwd (real filesystem). The first mount
# auto-activates the C-extension shim that re-routes File / Dir / IO /
# Kernel#require through the VFS — saves written to write_dir shadow the
# archive's stock copy on subsequent reads. `physfs` is statically linked
# into libembedded-ruby-vm and pre-provided at VM startup, so the require
# is a no-op kept for explicitness. EpsaStream and ArchiveMount are
# prepended as preludes by RubyScript — see
# CompilationEngine.ARCHIVE_PRELUDES.
require 'physfs'
ArchiveMount.mount!
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

STDERR.puts "Archive location: " + ENV.fetch('PSDK_EPSA_PATH', '<unset>')

# Self-guard: backup_saves.rb already assigned BACKUP_DIR earlier on this same
# reused compile VM. Same value, so the second assignment is a no-op — but
# Ruby still warns about it. BACKUP_SAVES/DEST_SAVES are unique to this script
# but also constants, so they get the same treatment on re-run.
BACKUP_DIR   = './ReleaseBackup'              unless defined?(BACKUP_DIR)
BACKUP_SAVES = File.join(BACKUP_DIR, 'Saves') unless defined?(BACKUP_SAVES)
DEST_SAVES   = File.join('Release', 'Saves')  unless defined?(DEST_SAVES)

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

# On Android, PSDK's save root is Release/ itself (save_root_path is "."),
# not only Release/Saves: .gameopts (persistent options such as --lang and
# --scale), GlobalPokedex.dat, GlobalHallOfFame.dat, deleted_saves/... live
# next to Saves and were lost on every APK update. Bring back every
# top-level entry of the previous Release that the new build doesn't have.
# Build outputs exist in both, so they are never overwritten.
NOT_RESTORED = %w[. .. Saves Error.log].freeze unless defined?(NOT_RESTORED)
if File.directory?(BACKUP_DIR)
  (Dir.entries(BACKUP_DIR) - NOT_RESTORED).each do |entry|
    src  = File.join(BACKUP_DIR, entry)
    dest = File.join('Release', entry)
    next if File.exist?(dest)

    if File.directory?(src)
      copy_directory_recursive(src, dest)
    elsif entry == '.gameopts'
      # Never carry a --util= launch payload: it would boot the game as a tool.
      File.write(dest, File.readlines(src, chomp: true).reject { |l| l.start_with?('--util=') }.join("\n"))
    else
      IO.copy_stream(src, dest)
    end
    STDERR.puts "Restored #{entry} from previous Release"
  end
end

# ReleaseBackup is cleaned up by CompilationEngine.commitRelease, which must
# drop the compilation marker first (crash-safe ordering).
