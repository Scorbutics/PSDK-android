# Moves a PSDK game's saves between desktop and Android, as a saves file
# (a ZIP) that both sides read and write. The Android app's counterpart is
# screens/src/androidMain/kotlin/com/psdk/saves/SaveTransfer.kt: keep the two
# in step.
#
# Self-contained (Ruby's standard library only, no gem), so it runs three
# ways:
#   - from the exporter: export_android.rb --export-saves / --import-saves
#   - on its own, from the game's folder, with any Ruby (2.7+), the game's
#     own included:
#       ruby save_transfer.rb export [<file.zip>] [--save-root DIR]
#       ruby save_transfer.rb import <file.zip>   [--save-root DIR]
#   - in an uncompiled PSDK project, copied into plugins/:
#       Game.rb --util=save_transfer export|import ...
#
# The saves file, paths relative to the game's save root:
#   psdk_saves.json   {"format": 1, "game", "platform", "exported_at", "files"}
#   README.txt        what to do with it
#   Saves/...         every save, except Saves/input.json (input.yml in older
#                     PSDK versions): a device's own keys, not another's; and
#                     Saves/Telemetry/: an install's own id and events
#   Global*.dat[.bak] files of the whole game, next to Saves (GlobalPokedex.dat...)
#   deleted_saves/... saves deleted in game
# Nothing else is read from it: a saves file can't write anywhere else.
#
# The save root is where PSDK writes (GamePlay::Save.save_root_path): the
# game's folder when it is writable, else %APPDATA% (or the home folder)
# /.<game title>/. On Android: the game's Release/ folder.
#
# An import replaces the saves: those in place move first into
# SavesBackup/<date>/ next to them, so nothing is lost.

require 'fileutils'
require 'json'
require 'time'
require 'zlib'

module SaveTransfer
  FORMAT   = 1
  MANIFEST = 'psdk_saves.json'
  README   = 'README.txt'
  BACKUP_DIR = 'SavesBackup'
  STAGING_DIR = '.saves_import'
  # The saves of a single game stay far below; more is not a saves file
  MAX_ENTRY_SIZE = 256 * 1024 * 1024
  # A device's own keys (input.yml: older PSDK versions)
  KEYS_FILES = %w[Saves/input.json Saves/input.yml].freeze

  Error = Class.new(StandardError)

  README_TEXT = <<~TEXT
    Saves of a PSDK game, exported to move them to another device.

    On Android: in the game, open the gamepad's tools button > Settings,
    then Saves > Import saves, and pick this file.

    On a computer: from the game's folder, run
        ruby save_transfer.rb import <this file>
    or unzip this file into the game's folder (where Game.rb is), letting it
    replace the files already there. When the game's folder is read-only,
    the game keeps its saves in %APPDATA%\\.<game title>\\ (Windows) or
    ~/.<game title>/ instead: unzip it there.

    psdk_saves.json and README.txt are not needed by the game.
  TEXT

  module_function

  # A path of the saves file the game reads: see the header
  def transferable?(path)
    return false unless safe?(path)

    if path.start_with?('Saves/')
      !KEYS_FILES.include?(path) && !path.start_with?('Saves/Telemetry/')
    elsif path.start_with?('deleted_saves/')
      true
    else
      path.match?(%r{\AGlobal[^/]*\.dat(\.bak)?\z})
    end
  end

  # Relative, inside its root, nothing a filesystem reads otherwise
  def safe?(path)
    # A name that isn't UTF-8 (in a saves file, on disk): no save of PSDK's
    return false unless path.valid_encoding? && path.encoding == Encoding::UTF_8
    return false if path.empty? || path.start_with?('/') || path.end_with?('/')
    return false if path.include?('\\') || path.include?("\0") || path.include?(':')

    path.split('/').none? { |part| part.empty? || part == '.' || part == '..' }
  end

  # The game's title, as PSDK has it: Data/configs/infos_config.json, else
  # project.studio's (in a project's folder). nil when neither says.
  def game_title(dir = '.')
    [['Data/configs/infos_config.json', 'gameTitle'], ['project.studio', 'title']].each do |file, key|
      title = JSON.parse(File.read(File.join(dir, file)))[key]
      return title if title.is_a?(String) && !title.strip.empty?
    rescue StandardError
      next
    end
    defined?(Configs) && Configs.respond_to?(:infos) ? Configs.infos.game_title : nil
  end

  # The folder PSDK saves into, run from the game's folder [dir]: see
  # GamePlay::Save.save_root_path and save_filename
  def save_root(dir = '.', title = game_title(dir))
    return File.expand_path(dir) if File.writable?(dir)

    home = [ENV['APPDATA'], Dir.home].compact.find { |path| File.writable?(path) }
    raise Error, 'No writable save folder: give it with --save-root' unless home
    raise Error, "The game's title isn't known (Data/configs/infos_config.json): give the save folder with --save-root" unless title

    File.join(home, ".#{title}")
  end

  # The saves in [root], as paths relative to it
  def files(root)
    return [] unless File.directory?(root)

    Dir.glob('**/*', File::FNM_DOTMATCH, base: root).select do |path|
      File.file?(File.join(root, path)) && transferable?(path)
    end.sort
  end

  # Writes the saves of [root] into the saves file [output]. Returns the
  # paths written.
  def export(root, output, game:, platform: 'desktop', now: Time.now)
    saves = files(root)
    raise Error, "No saves in #{root}" if saves.empty?

    manifest = { 'format' => FORMAT, 'game' => game, 'platform' => platform,
                 'exported_at' => now.getutc.iso8601, 'files' => saves }
    entries = [[MANIFEST, JSON.pretty_generate(manifest)], [README, README_TEXT]]
    entries += saves.map { |path| [path, File.binread(File.join(root, path))] }
    FileUtils.mkdir_p(File.dirname(File.expand_path(output)))
    File.open("#{output}.tmp", 'wb') { |io| Zip.write(io, entries, now) }
    File.rename("#{output}.tmp", output)
    saves
  ensure
    FileUtils.rm_f("#{output}.tmp")
  end

  # What a saves file holds: its manifest (empty when it has none, a ZIP made
  # by hand) and its saves, the paths an import would write
  def inspect_file(input)
    File.open(input, 'rb') do |io|
      entries = Zip.entries(io)
      manifest = entries.find { |entry| entry.name == MANIFEST }
      info = manifest ? parse_manifest(Zip.read(io, manifest)) : {}
      saves = entries.map(&:name).select { |name| transferable?(name) }
      raise Error, "#{input} holds no PSDK saves (a Saves folder)" if saves.empty?

      info.merge('saves' => saves)
    end
  rescue Zip::FormatError => e
    raise Error, "#{input} is not a saves file: #{e.message}"
  end

  # Replaces the saves of [root] with those of the saves file [input]; the
  # saves in place go to [backup_root]/<date>/. Read whole before anything
  # moves; a move that fails puts everything back. Returns [saves imported,
  # backup folder or nil].
  def import(input, root, backup_root: File.join(root, BACKUP_DIR), now: Time.now)
    staging = File.join(root, STAGING_DIR)
    FileUtils.rm_rf(staging)
    saves = []
    File.open(input, 'rb') do |io|
      Zip.entries(io).each do |entry|
        # A name given twice: the first one
        next if !transferable?(entry.name) || saves.include?(entry.name)

        target = File.join(staging, entry.name)
        FileUtils.mkdir_p(File.dirname(target))
        File.binwrite(target, Zip.read(io, entry))
        saves << entry.name
      end
    end
    raise Error, "#{input} holds no PSDK saves (a Saves folder)" if saves.empty?

    current = files(root)
    backup = current.empty? ? nil : free_dir(backup_root, now.strftime('%Y%m%d-%H%M%S'))
    backed_up = []
    placed = []
    begin
      current.each do |path|
        move(File.join(root, path), File.join(backup, path))
        backed_up << path
      end
      saves.each do |path|
        move(File.join(staging, path), File.join(root, path))
        placed << path
      end
    rescue SystemCallError => e
      # Back as before: the imported saves out, the saves in place back,
      # every file tried whatever failed before
      restored = placed.map { |path| attempt { FileUtils.rm_f(File.join(root, path)) } }.all?
      restored = backed_up.map { |path| attempt { move(File.join(backup, path), File.join(root, path)) } }.all? && restored
      raise Error, "Saves not imported (#{e.message}): nothing changed" if restored

      raise Error, "Saves not imported (#{e.message}): the saves in place before are in #{backup}"
    end
    [saves, backup]
  rescue Zip::FormatError => e
    raise Error, "#{input} is not a saves file: #{e.message}"
  ensure
    FileUtils.rm_rf(staging) if staging
  end

  # Whether [block] ran without a filesystem error
  def attempt
    yield
    true
  rescue SystemCallError
    false
  end

  def move(from, to)
    FileUtils.mkdir_p(File.dirname(to))
    File.rename(from, to)
  end

  # [root]/[name], or [name]-2, -3... when taken: never into another backup
  def free_dir(root, name)
    (1..).lazy.map { |n| File.join(root, n == 1 ? name : "#{name}-#{n}") }.find { |dir| !File.exist?(dir) }
  end

  def parse_manifest(data)
    manifest = JSON.parse(data)
    raise Error, 'Its psdk_saves.json is not a manifest' unless manifest.is_a?(Hash)
    raise Error, "Saves file format #{manifest['format']}: this version reads #{FORMAT}" if manifest['format'].to_i > FORMAT

    manifest
  rescue JSON::ParserError
    raise Error, 'Its psdk_saves.json is not JSON'
  end

  # The saves file's name: <title>-saves-<date>.zip
  def default_name(title, now = Time.now)
    name = (title || 'game').gsub(/[^\p{Alnum}\-_.]+/, '_')
    "#{name}-saves-#{now.strftime('%Y%m%d')}.zip"
  end

  # Command line: export|import [<file>] [--save-root DIR]. Returns the exit
  # status.
  def main(argv, out: $stdout)
    args = argv.dup
    root = nil
    if (index = args.index('--save-root'))
      root = args.delete_at(index + 1) or raise Error, '--save-root needs a folder'
      args.delete_at(index)
    end
    command, file = args
    unless %w[export import].include?(command)
      out.puts 'Usage: ruby save_transfer.rb export [<file.zip>] [--save-root DIR]'
      out.puts '       ruby save_transfer.rb import <file.zip>   [--save-root DIR]'
      return command.nil? || %w[-h --help].include?(command) ? 0 : 1
    end

    title = game_title
    root ||= save_root('.', title)
    case command
    when 'export'
      file ||= default_name(title)
      saves = export(root, file, game: title)
      out.puts "#{saves.size} save files of #{root} written to #{file}"
    when 'import'
      raise Error, 'import needs the saves file' unless file

      info = inspect_file(file)
      out.puts "#{file}: saves of #{info['game'] || 'an unnamed game'}" \
               "#{" from #{info['platform']}" if info['platform']}#{", #{info['exported_at']}" if info['exported_at']}"
      if info['game'] && title && info['game'] != title
        out.puts "  It is not #{title}'s: imported anyway (its saves may not load)"
      end
      saves, backup = import(file, root)
      out.puts "#{saves.size} save files imported into #{root}"
      out.puts "The saves in place before are in #{backup}" if backup
    end
    0
  rescue Error, SystemCallError => e
    warn e.message
    1
  end

  # The ZIP subset a saves file needs: stored or deflated entries, read
  # through the central directory (so data descriptors don't matter), no
  # ZIP64, no encryption. Spec: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
  module Zip
    FormatError = Class.new(StandardError)
    Entry = Struct.new(:name, :method, :crc, :compressed_size, :size, :header_offset)

    LOCAL = 0x04034b50
    CENTRAL = 0x02014b50
    EOCD = 0x06054b50
    STORED = 0
    DEFLATED = 8
    UTF8_FLAG = 0x0800
    MAX_COMMENT = 0xffff

    module_function

    # [[name, bytes]] into [io]
    def write(io, entries, time)
      dos_time = (time.hour << 11) | (time.min << 5) | (time.sec / 2)
      dos_date = ((time.year - 1980) << 9) | (time.month << 5) | time.day
      central = entries.map do |name, data|
        name = name.encode(Encoding::UTF_8).b
        data = data.b
        deflated = Zlib::Deflate.new(Zlib::DEFAULT_COMPRESSION, -Zlib::MAX_WBITS)
        packed = deflated.deflate(data, Zlib::FINISH)
        deflated.close
        crc = Zlib.crc32(data)
        offset = io.pos
        io.write([LOCAL, 20, UTF8_FLAG, DEFLATED, dos_time, dos_date, crc, packed.bytesize, data.bytesize,
                  name.bytesize, 0].pack('VvvvvvVVVvv'), name, packed)
        [CENTRAL, 20, 20, UTF8_FLAG, DEFLATED, dos_time, dos_date, crc, packed.bytesize, data.bytesize,
         name.bytesize, 0, 0, 0, 0, 0, offset].pack('VvvvvvvVVVvvvvvVV') + name
      end
      start = io.pos
      io.write(*central)
      io.write([EOCD, 0, 0, central.size, central.size, io.pos - start, start, 0].pack('VvvvvVVv'))
    end

    def entries(io)
      size = io.size
      tail_size = [size, MAX_COMMENT + 22].min
      io.seek(size - tail_size)
      tail = io.read(tail_size)
      eocd = tail.rindex([EOCD].pack('V')) or raise FormatError, 'not a ZIP file'
      count, cd_size, cd_offset = tail.byteslice(eocd + 10, 10).unpack('vVV')
      raise FormatError, 'ZIP64 is not supported' if cd_offset == 0xffffffff || count == 0xffff

      io.seek(cd_offset)
      cd = io.read(cd_size) || ''
      pos = 0
      Array.new(count) do
        raise FormatError, 'damaged central directory' unless cd.byteslice(pos, 4)&.unpack1('V') == CENTRAL

        flags, method, _time, _date, crc, csize, usize, nlen, elen, clen = cd.byteslice(pos + 8, 26).unpack('vvvvVVVvvv')
        offset = cd.byteslice(pos + 42, 4).unpack1('V')
        name = cd.byteslice(pos + 46, nlen)
        usize, csize, offset = zip64(cd.byteslice(pos + 46 + nlen, elen), usize, csize, offset)
        pos += 46 + nlen + elen + clen
        raise FormatError, 'encrypted entries are not supported' if flags & 1 == 1

        name.force_encoding(flags & UTF8_FLAG == 0 ? Encoding::IBM437 : Encoding::UTF_8)
        # Flagged UTF-8 but isn't: left invalid, so never a save (safe?)
        name = name.encode(Encoding::UTF_8) if name.valid_encoding?
        Entry.new(name, method, crc, csize, usize, offset)
      end
    end

    # The sizes and offset a central entry's ZIP64 extra field (0x0001) holds,
    # those of its 32-bit fields set to 0xffffffff, in this order. Tools
    # write it for small entries too (rubyzip's streams).
    def zip64(extra, usize, csize, offset)
      pos = 0
      while pos + 4 <= extra.bytesize
        id, size = extra.byteslice(pos, 4).unpack('vv')
        if id == 1
          values = extra.byteslice(pos + 4, size).unpack('Q<*')
          usize = values.shift || usize if usize == 0xffffffff
          csize = values.shift || csize if csize == 0xffffffff
          offset = values.shift || offset if offset == 0xffffffff
          break
        end
        pos += 4 + size
      end
      [usize, csize, offset]
    end

    def read(io, entry)
      raise FormatError, "#{entry.name}: too large" if entry.size > MAX_ENTRY_SIZE

      io.seek(entry.header_offset)
      header = io.read(30)
      raise FormatError, "#{entry.name}: damaged" unless header&.unpack1('V') == LOCAL

      nlen, elen = header.unpack('@26vv')
      io.seek(entry.header_offset + 30 + nlen + elen)
      packed = io.read(entry.compressed_size) || ''
      data = case entry.method
             when STORED then packed
             when DEFLATED
               inflate = Zlib::Inflate.new(-Zlib::MAX_WBITS)
               begin
                 inflate.inflate(packed)
               ensure
                 inflate.close
               end
             else raise FormatError, "#{entry.name}: compression #{entry.method} is not supported"
             end
      raise FormatError, "#{entry.name}: damaged" unless data.bytesize == entry.size && Zlib.crc32(data) == entry.crc

      data
    rescue Zlib::Error
      raise FormatError, "#{entry.name}: damaged"
    end
  end
end

# Run on its own, or by PSDK: Game.rb --util=save_transfer
if $PROGRAM_NAME == __FILE__
  exit SaveTransfer.main(ARGV)
elsif defined?(PARGV) && PARGV[:util].to_a.include?('save_transfer')
  SaveTransfer.main(ARGV.reject { |arg| arg.start_with?('--util') })
end
