# Android ResStringPool: the string table at the head of a binary
# AndroidManifest.xml (AXML) and of resources.arsc.
#
# Every attribute name, value and resource string of those files is an index
# into this pool, so renaming a string here renames it everywhere it's used,
# without touching anything else in the file: the indices don't move.
#
# Spec: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
#
# All multi-byte fields are little-endian.
#
# Layout:
#   uint16  type (0x0001)
#   uint16  header size (28)
#   uint32  chunk size
#   uint32  string count
#   uint32  style count
#   uint32  flags (0x1 sorted, 0x100 UTF-8, otherwise UTF-16)
#   uint32  strings start (from the chunk start)
#   uint32  styles start (from the chunk start, 0 without styles)
#   uint32  string offsets[string count] (from strings start)
#   uint32  style offsets[style count] (from styles start)
#   strings, 4-byte padded
#   styles (spans referencing string indices, kept as is)

module ResStringPool
  module_function

  ParseError = Class.new(StandardError)

  TYPE        = 0x0001
  HEADER_SIZE = 28
  SORTED_FLAG = 0x1
  UTF8_FLAG   = 0x100

  # A decoded pool: edit `strings` (UTF-8 Ruby strings), then `encode` it back.
  Pool = Struct.new(:strings, :flags, :style_offsets, :styles)

  # @param bytes  [String] the file holding the pool
  # @param offset [Integer] where the pool chunk starts
  # @return [Array(Pool, Integer)] the pool and its chunk size in `bytes`
  def parse(bytes, offset)
    type, header_size, size, string_count, style_count, flags, strings_start, styles_start =
      bytes.unpack("@#{offset}vvVVVVVV")
    raise ParseError, format('Not a string pool at 0x%x', offset) unless type == TYPE && header_size == HEADER_SIZE

    offsets       = bytes.unpack("@#{offset + header_size}V#{string_count}")
    style_offsets = bytes.unpack("@#{offset + header_size + (4 * string_count)}V#{style_count}")
    utf8          = flags.anybits?(UTF8_FLAG)
    strings = offsets.map { |string_offset| decode_string(bytes, offset + strings_start + string_offset, utf8) }
    styles  = style_count.zero? ? ''.b : bytes.byteslice(offset + styles_start, size - styles_start)
    [Pool.new(strings, flags, style_offsets, styles), size]
  end

  # @return [String] the pool chunk, in the pool's own encoding
  def encode(pool)
    utf8 = pool.flags.anybits?(UTF8_FLAG)
    data    = ''.b
    offsets = pool.strings.map do |string|
      data.size.tap { data << encode_string(string, utf8) }
    end
    data << ("\0" * (-data.size % 4))

    string_count  = pool.strings.size
    style_count   = pool.style_offsets.size
    strings_start = HEADER_SIZE + (4 * (string_count + style_count))
    styles_start  = style_count.zero? ? 0 : strings_start + data.size
    size          = strings_start + data.size + pool.styles.size
    # The strings changed: they may no longer be in order.
    flags = pool.flags & ~SORTED_FLAG
    [TYPE, HEADER_SIZE, size, string_count, style_count, flags, strings_start, styles_start].pack('vvVVVVVV') +
      offsets.pack('V*') + pool.style_offsets.pack('V*') + data + pool.styles
  end

  # A UTF-8 string: its length in UTF-16 units, then in bytes, each on 1 or 2
  # bytes (high bit set: 2 bytes), then the bytes and a NUL.
  # A UTF-16 string: its length in units on 1 or 2 uint16 (same rule), then
  # the units and a NUL unit.
  def decode_string(bytes, offset, utf8)
    if utf8
      _, offset = decode_length(bytes, offset, 'C', 0x80)
      length, offset = decode_length(bytes, offset, 'C', 0x80)
      bytes.byteslice(offset, length).force_encoding(Encoding::UTF_8)
    else
      length, offset = decode_length(bytes, offset, 'v', 0x8000)
      bytes.byteslice(offset, length * 2).force_encoding(Encoding::UTF_16LE).encode(Encoding::UTF_8)
    end
  end

  def decode_length(bytes, offset, unit, high_bit)
    unit_size = unit == 'C' ? 1 : 2
    first, second = bytes.unpack("@#{offset}#{unit}2")
    return [first, offset + unit_size] if first.nobits?(high_bit)

    [((first & ~high_bit) << (unit_size * 8)) | second, offset + (2 * unit_size)]
  end

  def encode_string(string, utf8)
    if utf8
      raw = string.encode(Encoding::UTF_8).b
      encode_length(string.encode(Encoding::UTF_16LE).bytesize / 2, 'C', 0x80) +
        encode_length(raw.size, 'C', 0x80) + raw + "\0"
    else
      raw = string.encode(Encoding::UTF_16LE).b
      encode_length(raw.size / 2, 'v', 0x8000) + raw + "\0\0"
    end
  end

  def encode_length(length, unit, high_bit)
    return [length].pack(unit) if length < high_bit

    unit_bits = unit == 'C' ? 8 : 16
    [(length >> unit_bits) | high_bit, length & ((1 << unit_bits) - 1)].pack("#{unit}2")
  end
end
