# Edits a compiled resources.arsc in place: the app name string and the
# package name, without decompiling the APK.
#
# Spec: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
#
# All multi-byte fields are little-endian.
#
# Layout:
#   uint16  type (0x0002), uint16 header size (12), uint32 file size
#   uint32  package count
#   string pool of the values (see res_string_pool.rb)
#   packages (0x0200):
#     uint16 type, uint16 header size, uint32 chunk size
#     uint32 id, char16 name[128]
#     uint32 type strings, uint32 last public type,
#     uint32 key strings, uint32 last public key (offsets from the chunk start)
#     the pool of type names ("string", "drawable"...), the pool of key names
#     ("app_name"...), then type specs (0x0202) and types (0x0201):
#       uint16 type, uint16 header size, uint32 chunk size
#       uint8 type id (1-based, in the type names), uint8 flags, uint16 0
#       uint32 entry count, uint32 entries start, config...
#       entry offsets: uint32 each (0xffffffff: no entry), or with the flags
#         0x01 (sparse) uint16 index + uint16 offset / 4 pairs, or
#         0x02 (16-bit offsets) uint16 offset / 4 each (0xffff: no entry)
#       entries: uint16 size, uint16 flags, uint32 key (in the key names),
#         then for a simple entry (no 0x0001 flag) a typed value:
#         uint16 size (8), uint8 0, uint8 data type, uint32 data
#         A compact entry (flag 0x0008) is 8 bytes: uint16 key, uint16 flags
#         (data type in the high byte), uint32 data.

require_relative 'res_string_pool'

class ResourceTable
  ParseError = Class.new(StandardError)

  TABLE_TYPE   = 0x0002
  PACKAGE_TYPE = 0x0200
  TYPE_TYPE    = 0x0201

  STRING_DATA_TYPE = 0x03
  NO_ENTRY         = 0xffffffff
  NO_ENTRY16       = 0xffff
  SPARSE_FLAG      = 0x01
  OFFSET16_FLAG    = 0x02
  COMPLEX_FLAG     = 0x0001
  COMPACT_FLAG     = 0x0008

  PACKAGE_NAME_UNITS = 128

  def initialize(bytes)
    type, @header_size, size = bytes.unpack('vvV')
    raise ParseError, 'Not a resources.arsc' unless type == TABLE_TYPE && size == bytes.bytesize

    @header = bytes.byteslice(0, @header_size).b
    @values, pool_size = ResStringPool.parse(bytes, @header_size)
    # The packages, after the value pool: their own offsets are relative to
    # each package, so a pool of another size doesn't move them.
    @packages = bytes.byteslice(@header_size + pool_size..).b
  end

  # Gives the string resource `name` (every configuration of it) the value
  # `value`. Returns how many values it changed.
  def set_string(name, value)
    indices = string_value_indices('string', name)
    indices.each { |index| @values.strings[index] = value }
    indices.size
  end

  # The values of the string resource `name`, one per configuration.
  def string_values(name)
    string_value_indices('string', name).map { |index| @values.strings[index] }
  end

  # Renames the package, like aapt2 --rename-resources-package: lookups by
  # package name (Resources.getIdentifier) then find the resources under the
  # new name.
  def package_name=(name)
    units = name.encode(Encoding::UTF_16LE).b
    raise ParseError, "Package name too long: #{name}" if units.bytesize >= PACKAGE_NAME_UNITS * 2

    each_package { |offset| @packages[offset + 12, PACKAGE_NAME_UNITS * 2] = units.ljust(PACKAGE_NAME_UNITS * 2, "\0") }
  end

  def to_bytes
    pool = ResStringPool.encode(@values)
    @header[4, 4] = [@header_size + pool.bytesize + @packages.bytesize].pack('V')
    @header + pool + @packages
  end

  private

  def each_package
    offset = 0
    while offset < @packages.bytesize
      type, _, size = @packages.unpack("@#{offset}vvV")
      raise ParseError, format('Broken chunk at 0x%x', offset) if size < 8

      yield offset, size if type == PACKAGE_TYPE
      offset += size
    end
  end

  # The value pool indices of the string resources `type_name`/`name`.
  def string_value_indices(type_name, name)
    indices = []
    each_package do |package, package_size|
      header_size, = @packages.unpack("@#{package + 2}v")
      type_strings, _, key_strings = @packages.unpack("@#{package + 268}VVV")
      type_names, = ResStringPool.parse(@packages, package + type_strings)
      key_names, = ResStringPool.parse(@packages, package + key_strings)
      type_id = type_names.strings.index(type_name)&.+(1)
      key = key_names.strings.index(name)
      next unless type_id && key

      offset = package + header_size
      while offset < package + package_size
        type, chunk_header_size, size = @packages.unpack("@#{offset}vvV")
        if type == TYPE_TYPE && @packages.getbyte(offset + 8) == type_id
          each_entry(offset, chunk_header_size) do |entry|
            index = string_value_index(entry, key)
            indices << index if index
          end
        end
        offset += size
      end
    end
    indices
  end

  # Yields the offset of each entry of the type chunk at `offset`.
  def each_entry(offset, header_size)
    flags = @packages.getbyte(offset + 9)
    entry_count, entries_start = @packages.unpack("@#{offset + 12}VV")
    table = offset + header_size
    entry_offsets =
      if flags.anybits?(SPARSE_FLAG)
        @packages.unpack("@#{table}v#{entry_count * 2}").each_slice(2).map { |_, quarter| quarter * 4 }
      elsif flags.anybits?(OFFSET16_FLAG)
        @packages.unpack("@#{table}v#{entry_count}").map { |quarter| quarter == NO_ENTRY16 ? NO_ENTRY : quarter * 4 }
      else
        @packages.unpack("@#{table}V#{entry_count}")
      end
    entry_offsets.each { |entry| yield offset + entries_start + entry unless entry == NO_ENTRY }
  end

  # The value pool index of the entry at `offset` when it is the string `key`.
  def string_value_index(offset, key)
    size_or_key, flags, data_or_key = @packages.unpack("@#{offset}vvV")
    if flags.anybits?(COMPACT_FLAG)
      return data_or_key if size_or_key == key && (flags >> 8) == STRING_DATA_TYPE
    elsif flags.nobits?(COMPLEX_FLAG) && data_or_key == key
      data_type, data = @packages.unpack("@#{offset + size_or_key + 3}CV")
      return data if data_type == STRING_DATA_TYPE
    end
    nil
  end
end
