# End-to-end tests of export_android.rb, run as Studio runs it (a child Ruby,
# cwd = the project root) on a minimal PSDK project, and read back with the
# app's own reader (EpsaStream): .psa, .epsa and, given a base APK, .apk.
#
# The APK tests need the base PSDK APK the app builds, in BASE_APK; they are
# skipped without it. With APKSIGNER and ZIPALIGN (Android SDK build-tools)
# they also check the APK with them.
#
# Run:  BASE_APK=app/build/outputs/apk/debug/PSDK-android-ruby3.0-debug.apk \
#       ruby exporter/test/test_export_android.rb

require 'minitest/autorun'
require 'fileutils'
require 'open3'
require 'rbconfig'
require 'tmpdir'
require 'zip'

require_relative '../lib/epsa_kdf'
require_relative '../lib/pkcs12_cert'
require_relative '../lib/apk_signing_block'
require_relative '../lib/apk_zip'
require_relative '../lib/apk_export'
require_relative '../../app/src/main/ruby/lib/epsa_stream'

class ExportAndroidTest < Minitest::Test
  EXPORT_ANDROID = File.expand_path('../export_android.rb', __dir__)
  KEYSTORE_PASS  = 'test-pass'
  # A 1x1 PNG.
  ICON = ['89504e470d0a1a0a0000000d4948445200000001000000010806000000' \
          '1f15c4890000000d49444154789c636001000000050001a5f645400000000049454e44ae426082'].pack('H*')

  def setup
    @dir = Dir.mktmpdir('export_android_test')
    @project = File.join(@dir, 'project')
    {
      'Game.rb' => "puts 'game'\n",
      'project.studio' => '{"title": "Exporter Test"}',
      'pokemonsdk/scripts/script_index.txt' => "pokemonsdk/scripts/00000 Engine.rb\n",
      'pokemonsdk/scripts/00000 Engine.rb' => "# engine\n",
      'scripts/00001 Project.rb' => "# project\n",
      'Data/test.dat' => 'data'
    }.each do |path, content|
      FileUtils.mkdir_p(File.dirname(File.join(@project, path)))
      File.write(File.join(@project, path), content)
    end
  end

  def teardown
    FileUtils.rm_rf(@dir)
  end

  def export(*args)
    output, status = Open3.capture2e({ 'PSDK_KS_PASS' => KEYSTORE_PASS }, RbConfig.ruby, EXPORT_ANDROID, *args,
                                     chdir: @project)
    assert status.success?, "export_android.rb #{args.join(' ')} failed:\n#{output}"
    output
  end

  def test_plain_archive
    output_path = File.join(@dir, 'game.psa')
    export('-o', output_path)
    assert_archive_contents(File.binread(output_path))
  end

  def test_encrypted_archive_for_a_cert
    keystore = generate_keystore
    cert_path = File.join(@dir, 'cert.der')
    File.binwrite(cert_path, Pkcs12Cert.signing_cert_der(keystore, KEYSTORE_PASS))
    output_path = File.join(@dir, 'game.epsa')
    export('-o', output_path, '--cert', cert_path)
    assert_archive_contents(decrypt(output_path, File.binread(cert_path)))
  end

  def test_apk
    base_apk = base_apk!
    keystore_dir = File.join(@dir, 'keys')
    output_path = File.join(@dir, 'out', 'Exporter Test.apk')
    File.binwrite(File.join(@dir, 'icon.png'), ICON)
    log = export('-o', output_path, '--apk', base_apk, '-k', keystore_dir, '--debug-mode',
                 '--icon', File.join(@dir, 'icon.png'))
    assert_equal (1..6).map { |step| "[#{step}/6]" }, log.scan(%r{\[\d/6\]})

    # Signed by the keystore's cert, and the archive is encrypted for it.
    cert_der = Pkcs12Cert.signing_cert_der(File.join(keystore_dir, 'debug.p12'), KEYSTORE_PASS)
    assert_equal cert_der, ApkSigningBlock.first_cert_der(output_path)
    assert_archive_contents(decrypt(extract(apk_entry(output_path, 'assets/game.epsa'), 'game.epsa'), cert_der))

    manifest = BinaryManifest.new(apk_entry(output_path, 'AndroidManifest.xml'))
    assert_equal 'com.psdk.exportertest', manifest.package
    refute manifest.add_meta_data(ApkExport::LAUNCH_MODE_META_DATA, 'any'), 'no launch mode meta-data'
    resources = ResourceTable.new(apk_entry(output_path, 'resources.arsc'))
    assert_equal ['Exporter Test'], resources.string_values('app_name')
    assert_equal ICON, apk_entry(output_path, 'res/drawable/logo.png')
    check_with_build_tools(output_path)

    # A re-export onto the exported APK (base = output) keeps one archive.
    export('-o', output_path, '--apk', output_path, '-k', keystore_dir, '--archive', 'psa')
    names = File.open(output_path, 'rb') { |apk| ApkZip.entries(apk).map(&:name) }
    assert_equal ['assets/game.psa'], names.grep(%r{\Aassets/game\.})
    assert_archive_contents(apk_entry(output_path, 'assets/game.psa'))
    refute File.exist?("#{output_path}.part")
    check_with_build_tools(output_path)
  end

  def test_apk_with_an_ec_key
    base_apk = base_apk!
    key = OpenSSL::PKey::EC.generate('prime256v1')
    cert = OpenSSL::X509::Certificate.new
    cert.version = 2
    cert.serial = 1
    cert.subject = cert.issuer = OpenSSL::X509::Name.parse('CN=EC test')
    cert.public_key = key
    cert.not_before = Time.now
    cert.not_after = Time.now + (365 * 24 * 3600)
    cert.sign(key, OpenSSL::Digest.new('SHA256'))
    keystore = File.join(@dir, 'ec.p12')
    File.binwrite(keystore, OpenSSL::PKCS12.create(KEYSTORE_PASS, 'key', key, cert).to_der)

    output_path = File.join(@dir, 'ec.apk')
    export('-o', output_path, '--apk', base_apk, '-k', keystore)
    assert_equal cert.to_der, ApkSigningBlock.first_cert_der(output_path)
    check_with_build_tools(output_path)
  end

  private

  def base_apk!
    base_apk = ENV['BASE_APK']
    skip 'BASE_APK not set' unless base_apk
    File.expand_path(base_apk)
  end

  def generate_keystore
    path = File.join(@dir, 'test.p12')
    Pkcs12Cert.generate(p12_path: path, password: KEYSTORE_PASS)
    path
  end

  # The plaintext of the .epsa at `path`, read with the app's EpsaStream, the
  # keys derived from `cert_der` as the app does.
  def decrypt(path, cert_der)
    build_id = File.binread(path, EpsaFormat::BUILD_ID_SIZE, EpsaFormat::BUILD_ID_OFFSET)
    kdf_version = File.binread(path, 1, EpsaFormat::KDF_VERSION_OFFSET).ord
    keys = EpsaKdf.derive_v4(cert_der, build_id, kdf_version)
    stream = EpsaStream.new(path, enc_key: keys[:enc_key], mac_key: keys[:mac_key])
    stream.read(stream.length)
  ensure
    stream&.close
  end

  def extract(bytes, name)
    path = File.join(@dir, name)
    File.binwrite(path, bytes)
    path
  end

  def assert_archive_contents(zip_bytes)
    names = Zip::File.open_buffer(zip_bytes).entries.map(&:name)
    assert_includes names, 'Game.rb'
    assert_includes names, 'Data/test.dat'
    assert_includes names, 'scripts/00001 Project.rb'
    assert_includes names, 'pokemonsdk/scripts/mega_script.deflate'
  end

  def apk_entry(apk_path, name)
    File.open(apk_path, 'rb') do |apk|
      ApkExport.read_entry(apk, ApkExport.find_entry(ApkZip.entries(apk), name))
    end
  end

  def check_with_build_tools(apk_path)
    if ENV['APKSIGNER']
      output, status = Open3.capture2e(ENV['APKSIGNER'], 'verify', '--min-sdk-version', '26', apk_path)
      assert status.success?, "apksigner verify failed:\n#{output}"
    end
    return unless ENV['ZIPALIGN']

    output, status = Open3.capture2e(ENV['ZIPALIGN'], '-c', '-P', '16', '4', apk_path)
    assert status.success?, "zipalign -c failed:\n#{output}"
  end
end
