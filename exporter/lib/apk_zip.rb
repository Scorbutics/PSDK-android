# Rewrites an APK's ZIP entries, aligned for Android: what apktool and
# zipalign did, without either.
#
# Unchanged entries are copied as they are (no recompression). Replaced and
# new entries come from bytes or from a file, stored or deflated. Stored
# entries get the alignment Android maps them with: 4 bytes, 16 KiB for
# native libraries (16 KiB page devices). The padding goes into an extra
# field 0xd935, as apksigner and zipalign -P write it.
#
# The central directory isn't written here: ApkV2Signer writes it after the
# signing block that goes before it.
#
# Spec: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
#
# All multi-byte fields are little-endian. No ZIP64: an APK's entries and
# offsets have to fit in 32 bits.

require 'zlib'

module ApkZip
  module_function

  FormatError = Class.new(StandardError)

  LOCAL_HEADER_SIGNATURE   = 0x04034b50
  CENTRAL_HEADER_SIGNATURE = 0x02014b50
  EOCD_SIGNATURE           = 0x06054b50
  EOCD_SIZE                = 22
  STORED                   = 0
  DEFLATED                 = 8
  DATA_DESCRIPTOR_FLAG     = 0x0008
  ALIGNMENT_EXTRA_ID       = 0xd935
  ALIGNMENT                = 4
  LIBRARY_ALIGNMENT        = 16_384
  MAX_32                   = 0xffffffff
  COPY_CHUNK               = 1 << 20

  # An entry to write. Its data comes from `data` (bytes, already compressed
  # if `method` is DEFLATED), from the file `path` (stored), or else from the
  # APK it was read from, at `data_offset`.
  Entry = Struct.new(:name, :method, :crc, :compressed_size, :size, :dos_time, :dos_date, :flags,
                     :version_made_by, :version_needed, :external_attributes, :data_offset, :data, :path,
                     keyword_init: true)

  # @param io [IO] the APK, opened in binary mode
  # @return [Array<Entry>] its entries, in central directory order
  def entries(io)
    cd_offset, count = read_eocd(io)
    io.seek(cd_offset)
    Array.new(count) do
      signature, version_made_by, version_needed, flags, method, dos_time, dos_date, crc, compressed_size, size,
        name_length, extra_length, comment_length, _, _, external_attributes, local_offset = io.read(46).unpack('VvvvvvvVVVvvvvvVV')
      raise FormatError, 'Broken central directory' unless signature == CENTRAL_HEADER_SIGNATURE

      name = io.read(name_length).force_encoding(Encoding::UTF_8)
      io.seek(extra_length + comment_length, IO::SEEK_CUR)
      Entry.new(name: name, method: method, crc: crc, compressed_size: compressed_size, size: size,
                dos_time: dos_time, dos_date: dos_date, flags: flags & ~DATA_DESCRIPTOR_FLAG,
                version_made_by: version_made_by, version_needed: version_needed,
                external_attributes: external_attributes, data_offset: local_offset)
    end.each do |entry|
      # The data starts after the local header, whose extra field can differ
      # from the central directory's.
      io.seek(entry.data_offset + 26)
      name_length, extra_length = io.read(4).unpack('vv')
      entry.data_offset += 30 + name_length + extra_length
    end
  end

  # A new entry, deflated.
  def deflated(name, data)
    deflater = Zlib::Deflate.new(Zlib::BEST_COMPRESSION, -Zlib::MAX_WBITS)
    compressed = deflater.deflate(data, Zlib::FINISH)
    deflater.close
    new_entry(name, DEFLATED, Zlib.crc32(data), compressed.bytesize, data.bytesize, data: compressed)
  end

  # A new entry, stored.
  def stored(name, data)
    new_entry(name, STORED, Zlib.crc32(data), data.bytesize, data.bytesize, data: data)
  end

  # A new entry, stored, streamed from the file `path` (the game archive can
  # weigh gigabytes: it never sits in memory).
  def stored_file(name, path)
    crc = 0
    File.open(path, 'rb') do |file|
      while (chunk = file.read(COPY_CHUNK))
        crc = Zlib.crc32(chunk, crc)
      end
    end
    size = File.size(path)
    new_entry(name, STORED, crc, size, size, path: path)
  end

  def new_entry(name, method, crc, compressed_size, size, data: nil, path: nil)
    time = Time.now
    Entry.new(name: name, method: method, crc: crc, compressed_size: compressed_size, size: size,
              dos_time: (time.hour << 11) | (time.min << 5) | (time.sec / 2),
              dos_date: ((time.year - 1980) << 9) | (time.month << 5) | time.day,
              flags: name.ascii_only? ? 0 : 0x0800, version_made_by: 0, version_needed: method == STORED ? 10 : 20,
              external_attributes: 0, data: data, path: path)
  end

  # Writes the local entries to `io`, from its start.
  #
  # @param source [IO] the APK the copied entries come from
  # @return [Array(Integer, String, String)] the central directory offset
  #   (where the entries end), the central directory and the EOCD
  def write(io, entries, source)
    raise FormatError, "Too many entries for an APK: #{entries.size}" if entries.size >= 0xffff

    io.seek(0)
    central_directory = ''.b
    entries.each do |entry|
      local_offset = io.pos
      name = entry.name.b
      extra = ''.b
      if entry.method == STORED
        alignment = entry.name.start_with?('lib/') && entry.name.end_with?('.so') ? LIBRARY_ALIGNMENT : ALIGNMENT
        padding = -(local_offset + 30 + name.bytesize + 6) % alignment
        extra = [ALIGNMENT_EXTRA_ID, 2 + padding, alignment].pack('vvv') + ("\0" * padding)
      end
      fields = [entry.version_needed, entry.flags, entry.method, entry.dos_time, entry.dos_date,
                entry.crc, entry.compressed_size, entry.size]
      io.write([LOCAL_HEADER_SIGNATURE, *fields, name.bytesize, extra.bytesize].pack('VvvvvvVVVvv'), name, extra)
      write_data(io, entry, source)
      raise FormatError, "APK too large (over 4 GiB) at #{entry.name}" if io.pos >= MAX_32

      central_directory << [CENTRAL_HEADER_SIGNATURE, entry.version_made_by, *fields, name.bytesize, 0, 0, 0, 0,
                            entry.external_attributes, local_offset].pack('VvvvvvvVVVvvvvvVV') << name
    end
    cd_offset = io.pos
    eocd = [EOCD_SIGNATURE, 0, 0, entries.size, entries.size, central_directory.bytesize, cd_offset, 0].pack('VvvvvVVv')
    [cd_offset, central_directory, eocd]
  end

  def write_data(io, entry, source)
    if entry.data
      io.write(entry.data)
    elsif entry.path
      File.open(entry.path, 'rb') { |file| IO.copy_stream(file, io) }
    else
      IO.copy_stream(source, io, entry.compressed_size, entry.data_offset)
    end
  end

  # The EOCD sits at the end, after a comment of up to 64 KiB.
  def read_eocd(io)
    size = io.size
    tail_size = [size, EOCD_SIZE + 0xffff].min
    io.seek(size - tail_size)
    tail = io.read(tail_size)
    position = tail.rindex([EOCD_SIGNATURE].pack('V'))
    raise FormatError, 'Not a ZIP file (no end of central directory)' unless position

    _, _, _, _, count, _, cd_offset = tail.unpack("@#{position}VvvvvVV")
    raise FormatError, 'ZIP64 APKs are not supported' if count == 0xffff || cd_offset == MAX_32

    [cd_offset, count]
  end
end
