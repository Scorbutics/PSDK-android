# Builds a game's APK from the base PSDK APK: steps 2 to 6 of
# export_android.rb, once the game archive is built (step 1).
#
# The APK is edited as it is, with no decompile / rebuild: its compiled
# manifest and resource table take the few edits below in place (see
# binary_manifest.rb and resource_table.rb), every other entry is copied as
# it is. Then it's aligned and signed (apk_zip.rb, apk_v2_signer.rb).

require 'fileutils'
require 'zlib'

require_relative 'apk_zip'
require_relative 'apk_v2_signer'
require_relative 'binary_manifest'
require_relative 'resource_table'

module ApkExport
  module_function

  Error = Class.new(StandardError)

  # The manifest <meta-data> EntryActivity.classicDefaultMode reads.
  LAUNCH_MODE_META_DATA = 'com.psdk.CLASSIC_DEFAULT_MODE'
  DEBUG_LAUNCH_MODE     = 'debug_uncompiled'
  ARCHIVE_ENTRIES       = %w[assets/game.epsa assets/game.psa].freeze
  # The icon resource the manifest uses, @drawable/logo (or a mipmap).
  ICON_RESOURCE         = 'logo'
  ICON_TYPES            = %w[drawable mipmap].freeze

  # @param archive_path [String] the game archive, bundled as assets/<archive_name>
  # @param archive_name [String] game.epsa or game.psa
  # @param icon_path    [String, nil] a PNG replacing the base APK's icon
  # @param debug_mode   [Boolean] launch the game in DEBUG_UNCOMPILED
  # @param signing_key  [OpenSSL::PKey::PKey] the keystore's key (RSA or EC)
  # @param signing_cert [OpenSSL::X509::Certificate] its certificate
  # @raise [Error] on a base APK it can't edit, or a key it can't sign with
  def export(base_apk_path:, output_path:, archive_path:, archive_name:, package_id:, app_name:,
             icon_path:, debug_mode:, signing_key:, signing_cert:)
    File.open(base_apk_path, 'rb') do |base_apk|
      puts '[2/6] Reading base APK...'
      entries = ApkZip.entries(base_apk)
      puts "  #{entries.size} entries"

      puts '[3/6] Customizing app identity...'
      customize(base_apk, entries, package_id: package_id, app_name: app_name, icon_path: icon_path,
                                   debug_mode: debug_mode)

      puts '[4/6] Injecting game archive into assets...'
      # The app plays the first archive it finds: drop the other kind a
      # re-export onto an exported APK would leave behind.
      entries.reject! { |entry| ARCHIVE_ENTRIES.include?(entry.name) }
      # Stored: an .epsa is encrypted (nothing to compress) and a .psa is a
      # ZIP already. The app reads it as a stream either way.
      entries << ApkZip.stored_file("assets/#{archive_name}", archive_path)
      puts "  Injected #{archive_name} into assets/"

      write_signed(base_apk, entries, output_path, signing_key, signing_cert)
    end
  rescue ApkZip::FormatError => e
    raise Error, "#{base_apk_path}: #{e.message}"
  rescue BinaryManifest::ParseError, ResourceTable::ParseError, ResStringPool::ParseError => e
    raise Error, "Unexpected base APK resources: #{e.message}"
  rescue ApkV2Signer::SigningError => e
    raise Error, e.message
  end

  # Replaces the manifest, the resource table and the icon in `entries`.
  def customize(base_apk, entries, package_id:, app_name:, icon_path:, debug_mode:)
    # AndroidManifest.xml — rename the package in every string that holds
    # it: package="...", provider authorities, and auto-generated permissions
    # like DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
    manifest = BinaryManifest.new(read_entry(base_apk, find_entry(entries, 'AndroidManifest.xml')))
    old_package = manifest.package
    manifest.rename_package(old_package, package_id)
    puts "  Package: #{old_package} -> #{package_id}"

    # Update versionCode so Android allows installing over a previous build
    version_code = Time.now.to_i / 60
    manifest.version_code = version_code
    puts "  versionCode: #{version_code}"

    # Bake the launch mode into the APK. Read by EntryActivity at cold start
    # (see EntryActivity.classicDefaultMode). Absent the tag, the app
    # defaults to RELEASE — so we ONLY inject it for --debug-mode builds.
    if debug_mode
      if manifest.add_meta_data(LAUNCH_MODE_META_DATA, DEBUG_LAUNCH_MODE)
        puts "  Manifest: injected CLASSIC_DEFAULT_MODE=#{DEBUG_LAUNCH_MODE}"
      else
        # Re-export onto an already-debug APK: leave the existing tag alone.
        puts '  Manifest already carries CLASSIC_DEFAULT_MODE; left as-is.'
      end
    end
    puts "  Launch mode: #{debug_mode ? DEBUG_LAUNCH_MODE : 'release'}"

    # resources.arsc — change app_name, and the package name, for lookups by
    # name (Resources.getIdentifier).
    resources = ResourceTable.new(read_entry(base_apk, find_entry(entries, 'resources.arsc')))
    raise Error, 'No string/app_name in the base APK resources' if resources.set_string('app_name', app_name).zero?

    resources.package_name = package_id
    puts "  App name: #{app_name}"

    # The icon's files, as the resource table names them: a release build
    # shortens their paths (res/zu.png).
    icon = icon_path && File.binread(icon_path)
    icon_files = ICON_TYPES.flat_map { |type| resources.string_values(ICON_RESOURCE, type: type) }
                           .select { |file| file.end_with?('.png') }
    warn "  No #{ICON_RESOURCE}.png icon in the base APK: its icon is kept" if icon && icon_files.empty?
    entries.map! do |entry|
      case entry.name
      when 'AndroidManifest.xml' then ApkZip.deflated(entry.name, manifest.to_bytes)
      # Stored, as aapt2 does: Android maps the resource table from the APK.
      when 'resources.arsc' then ApkZip.stored(entry.name, resources.to_bytes)
      when *icon_files
        next entry unless icon

        puts "  Icon replaced: #{entry.name}"
        ApkZip.stored(entry.name, icon)
      else entry
      end
    end
  end

  # Writes and signs the APK. It goes next to the output, then is renamed
  # over it once signed: the output may be the base APK itself (a
  # re-export), still being read, and a failed export leaves no half-written
  # APK behind.
  def write_signed(base_apk, entries, output_path, signing_key, signing_cert)
    puts '[5/6] Writing APK...'
    FileUtils.mkdir_p(File.dirname(output_path))
    partial_output = "#{output_path}.part"
    File.open(partial_output, 'w+b') do |output|
      cd_offset, central_directory, eocd = ApkZip.write(output, entries, base_apk)

      puts '[6/6] Signing APK...'
      ApkV2Signer.sign(output, cd_offset: cd_offset, central_directory: central_directory, eocd: eocd,
                               key: signing_key, cert: signing_cert)
    end
    base_apk.close
    File.rename(partial_output, output_path)
    puts "  Signed (v2) by #{signing_cert.subject}"
  ensure
    FileUtils.rm_f(partial_output) if partial_output
  end

  def read_entry(apk, entry)
    apk.seek(entry.data_offset)
    data = apk.read(entry.compressed_size)
    entry.method == ApkZip::DEFLATED ? Zlib::Inflate.new(-Zlib::MAX_WBITS).inflate(data) : data
  end

  def find_entry(entries, name)
    entries.find { |entry| entry.name == name } or raise Error, "No #{name} in the base APK"
  end
end
