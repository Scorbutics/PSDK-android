# Edits a compiled (binary XML, "AXML") AndroidManifest.xml in place: the
# package rename, the versionCode and the launch-mode <meta-data> that
# export_android.rb needs, without decompiling the APK.
#
# Spec: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
#
# All multi-byte fields are little-endian.
#
# Layout:
#   uint16  type (0x0003), uint16 header size (8), uint32 file size
#   string pool (see res_string_pool.rb)
#   resource map (0x0180): uint32 attribute resource IDs, one per string
#     index, for the attribute names (android:name is 0x01010003...)
#   nodes: namespace (0x0100/0x0101), start element (0x0102), end element
#     (0x0103), text (0x0104). Each one:
#       uint16 type, uint16 header size (16), uint32 node size
#       uint32 line number, uint32 comment (string index, or 0xffffffff)
#     A start element then has:
#       uint32 namespace, uint32 name (string indices)
#       uint16 attribute start (20), uint16 attribute size (20),
#       uint16 attribute count, uint16 id/class/style indices
#       attributes: uint32 namespace, uint32 name, uint32 raw value
#         (string indices, or 0xffffffff), then a typed value:
#         uint16 size (8), uint8 0, uint8 data type, uint32 data
#     An end element: uint32 namespace, uint32 name.

require_relative 'res_string_pool'

class BinaryManifest
  ParseError = Class.new(StandardError)

  XML_TYPE           = 0x0003
  RESOURCE_MAP_TYPE  = 0x0180
  START_ELEMENT_TYPE = 0x0102
  END_ELEMENT_TYPE   = 0x0103
  NO_INDEX           = 0xffffffff

  STRING_DATA_TYPE = 0x03
  INT_DATA_TYPE    = 0x10

  ATTR_NAME         = 0x01010003
  ATTR_VALUE        = 0x01010024
  ATTR_VERSION_CODE = 0x0101021b

  def initialize(bytes)
    type, header_size, size = bytes.unpack('vvV')
    raise ParseError, 'Not a binary AndroidManifest.xml' unless type == XML_TYPE && size == bytes.bytesize

    @pool, pool_size = ResStringPool.parse(bytes, header_size)
    # Everything after the pool. The edits below don't move the string
    # indices, so these bytes only change where an edit writes into them.
    @nodes = bytes.byteslice(header_size + pool_size..).b
    resource_map = each_node.find { |_, node_type| node_type == RESOURCE_MAP_TYPE }
    @resource_ids = resource_map ? @nodes.unpack("@#{resource_map[0] + 8}V#{(resource_map[2] - 8) / 4}") : []
  end

  # The package="..." of the <manifest> element.
  def package
    _, _, value = attributes(manifest_element).find { |_, name| @pool.strings[name] == 'package' }
    raise ParseError, 'No package attribute on <manifest>' unless value

    @pool.strings[value]
  end

  # Renames the package in every string of the manifest: the package
  # attribute, but also the authorities and permissions derived from it
  # (<package>.provider, <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION).
  def rename_package(old_package, new_package)
    @pool.strings.map! { |string| string.gsub(old_package, new_package) }
  end

  def version_code=(code)
    offset, = attributes(manifest_element).find { |_, name| @resource_ids[name] == ATTR_VERSION_CODE }
    raise ParseError, 'No android:versionCode on <manifest>' unless offset

    @nodes[offset + 8, 12] = [NO_INDEX, 8, 0, INT_DATA_TYPE, code].pack('VvCCV')
  end

  # Adds <meta-data android:name="name" android:value="value"/> at the end of
  # <application>, unless a meta-data of that name is there already (a
  # re-export onto an exported APK). Returns whether it added it.
  #
  # The element is a copy of a meta-data the manifest already has (androidx
  # startup declares some): it brings the "meta-data" element name and the
  # android:name and android:value attribute names, whose string indices are
  # tied to the resource map. Only the two new values join the pool, at its
  # end, where they move no index.
  def add_meta_data(name, value)
    return false if @pool.strings.include?(name)

    template, = each_node.find do |offset, node_type|
      next false unless node_type == START_ELEMENT_TYPE && @pool.strings[element_name(offset)] == 'meta-data'

      attribute_ids = attributes(offset).map { |_, attribute_name| @resource_ids[attribute_name] }
      attribute_ids.sort == [ATTR_NAME, ATTR_VALUE]
    end
    raise ParseError, 'No <meta-data android:name android:value> to copy in the manifest' unless template

    application_end, = each_node.find do |offset, node_type|
      node_type == END_ELEMENT_TYPE && @pool.strings[element_name(offset)] == 'application'
    end
    raise ParseError, 'No </application> in the manifest' unless application_end

    values = { ATTR_NAME => add_string(name), ATTR_VALUE => add_string(value) }
    _, _, template_size = @nodes.unpack("@#{template}vvV")
    element = @nodes.byteslice(template, template_size)
    attributes(template).each do |attribute_offset, attribute_name|
      index = values[@resource_ids[attribute_name]]
      element[attribute_offset - template + 8, 12] = [index, 8, 0, STRING_DATA_TYPE, index].pack('VvCCV')
    end
    line, = @nodes.unpack("@#{template + 8}V")
    namespace, element_name_index = @nodes.unpack("@#{template + 16}VV")
    element_end = [END_ELEMENT_TYPE, 16, 24, line, NO_INDEX, namespace, element_name_index].pack('vvVVVVV')
    @nodes.insert(application_end, element + element_end)
    true
  end

  def to_bytes
    pool = ResStringPool.encode(@pool)
    [XML_TYPE, 8, 8 + pool.bytesize + @nodes.bytesize].pack('vvV') + pool + @nodes
  end

  private

  # Yields each node of @nodes as [offset, type, size].
  def each_node
    return enum_for(:each_node) unless block_given?

    offset = 0
    while offset < @nodes.bytesize
      type, _, size = @nodes.unpack("@#{offset}vvV")
      raise ParseError, format('Broken node at 0x%x', offset) if size < 8

      yield offset, type, size
      offset += size
    end
  end

  def manifest_element
    offset, = each_node.find { |_, type| type == START_ELEMENT_TYPE }
    raise ParseError, 'No <manifest> element' unless offset && @pool.strings[element_name(offset)] == 'manifest'

    offset
  end

  def element_name(offset)
    @nodes.unpack1("@#{offset + 20}V")
  end

  # The attributes of the start element at `offset`, as
  # [offset of the attribute, name index, raw value index].
  def attributes(offset)
    header_size, = @nodes.unpack("@#{offset + 2}v")
    attribute_start, attribute_size, count = @nodes.unpack("@#{offset + header_size + 8}vvv")
    Array.new(count) do |i|
      attribute = offset + header_size + attribute_start + (i * attribute_size)
      _, name, value = @nodes.unpack("@#{attribute}VVV")
      [attribute, name, value]
    end
  end

  def add_string(string)
    @pool.strings << string
    @pool.strings.size - 1
  end
end
