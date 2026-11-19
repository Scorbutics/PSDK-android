# End-to-end tests of export_android.rb, run as Studio runs it (a child Ruby,
# cwd = the project root) on a minimal PSDK project, and read back with the
# app's own reader (EpsaStream): .psa, .epsa and, given a base APK, .apk.
#
# The APK tests need a base PSDK APK the app builds, in BASE_APK (debug or
# release: a release one has shortened resource paths); they are skipped
# without it. With APKSIGNER and ZIPALIGN (Android SDK build-tools)
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
require_relative '../lib/base_apk'
require_relative '../lib/remote_game'
require_relative '../lib/splash_assets'
require 'digest'
require 'json'
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
    output, status = run_export(*args)
    assert status.success?, "export_android.rb #{args.join(' ')} failed:\n#{output}"
    output
  end

  # The exporter refuses: its message
  def export_refused(*args)
    output, status = run_export(*args)
    refute status.success?, "export_android.rb #{args.join(' ')} should have failed:\n#{output}"
    output
  end

  def run_export(*args)
    Open3.capture2e({ 'PSDK_KS_PASS' => KEYSTORE_PASS }, RbConfig.ruby, EXPORT_ANDROID, *args, chdir: @project)
  end

  def test_plain_archive
    output_path = File.join(@dir, 'game.psa')
    export('-o', output_path)
    assert_archive_contents(File.binread(output_path))
  end

  def test_compiled_archive_keeps_the_game_touch_layout
    {
      'Release/Game.yarb' => RubyVM::InstructionSequence.compile('1').to_binary,
      'Release/Data/0.dat' => 'compiled data',
      'Data/touch_layout.json' => '{"version": 1, "buttons": {"run": {"key": "LShift", "image": "touch/run.png"}, ' \
                                  '"out": {"key": "A", "image": "../Game.rb"}, "out2": {"key": "B", "image": "..\\\\Game.rb"}, ' \
                                  '"same": {"key": "C", "image": "touch_layout.json"}, "nul": {"key": "D", "image": "touch/run\\u0000.png"}}}',
      'Data/touch/run.png' => ICON
    }.each do |path, content|
      FileUtils.mkdir_p(File.dirname(File.join(@project, path)))
      File.binwrite(File.join(@project, path), content)
    end
    output_path = File.join(@dir, 'game.psa')
    export('--compiled', '-o', output_path)
    names = Zip::File.open_buffer(File.binread(output_path)).entries.map(&:name)
    assert_includes names, 'Data/0.dat'
    assert_includes names, 'Data/touch_layout.json'
    assert_includes names, 'Data/touch/run.png'
    refute names.any? { |name| name.include?('..') }, names.inspect
    assert_equal names.uniq, names
  end

  def test_compiled_archive_with_an_unreadable_touch_layout
    {
      'Release/Game.yarb' => RubyVM::InstructionSequence.compile('1').to_binary,
      'Data/touch_layout.json' => "{\"version\": 1, \"buttons\": {\"bad\": {\"key\": \"A\", \"image\": \"t\xFF/r.png\"}, " \
                                  "\"run\": {\"key\": \"B\", \"image\": \"touch/run.png\"}}}".b,
      'Data/touch/run.png' => ICON
    }.each do |path, content|
      FileUtils.mkdir_p(File.dirname(File.join(@project, path)))
      File.binwrite(File.join(@project, path), content)
    end
    output_path = File.join(@dir, 'game.psa')
    export('--compiled', '-o', output_path)
    names = Zip::File.open_buffer(File.binread(output_path)).entries.map(&:name)
    assert_includes names, 'Data/touch_layout.json'
    assert_includes names, 'Data/touch/run.png'
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
    log = export('-o', output_path, '--apk', base_apk, '-k', keystore_dir, '--full', '--debug-mode',
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
    icon_files = resources.string_values('logo', type: 'drawable')
    refute_empty icon_files
    icon_files.each { |file| assert_equal ICON, apk_entry(output_path, file) }
    check_with_build_tools(output_path)

    # A re-export onto the exported APK (base = output) keeps one archive.
    export('-o', output_path, '--apk', output_path, '-k', keystore_dir, '--full', '--archive', 'psa')
    names = File.open(output_path, 'rb') { |apk| ApkZip.entries(apk).map(&:name) }
    assert_equal ['assets/game.psa'], names.grep(%r{\Aassets/game\.})
    assert_archive_contents(apk_entry(output_path, 'assets/game.psa'))
    refute File.exist?("#{output_path}.part")
    check_with_build_tools(output_path)
  end

  def test_small_apk_and_its_hosted_game
    base_apk = base_apk!
    keystore_dir = File.join(@dir, 'keys')
    out_dir = File.join(@dir, 'out')
    output_path = File.join(out_dir, 'Exporter Test.apk')
    url = 'https://example.com/releases/latest/download/android-game.json'
    log = export('-o', output_path, '--apk', base_apk, '-k', keystore_dir, '--remote', url, '--game-version', '7')
    assert_equal (1..6).map { |step| "[#{step}/6]" }, log.scan(%r{\[\d/6\]})

    # The APK: no game inside, where to get it
    names = File.open(output_path, 'rb') { |apk| ApkZip.entries(apk).map(&:name) }
    assert_empty names.grep(%r{\Aassets/game\.(e?psa)\z})
    assert_equal({ 'format' => 1, 'manifest_url' => url }, JSON.parse(apk_entry(output_path, RemoteGame::ASSET)))
    # A game without its infos: no splash of its own (the app's meanwhile)
    assert_empty names.grep(%r{\A#{SplashAssets::ASSET_DIR}/})
    assert_match(/The game's splash is not in the APK/, log)
    check_with_build_tools(output_path)

    # Next to it, the game and its manifest, to upload
    archive = File.join(out_dir, 'game.epsa')
    manifest = JSON.parse(File.read(File.join(out_dir, RemoteGame::MANIFEST_NAME)))
    cert_der = Pkcs12Cert.signing_cert_der(File.join(keystore_dir, 'debug.p12'), KEYSTORE_PASS)
    assert_equal 'Exporter Test', manifest['title']
    assert_equal '7', manifest['version']
    assert_equal({ 'url' => 'game.epsa', 'size' => File.size(archive),
                   'sha256' => Digest::SHA256.file(archive).hexdigest }, manifest['archive'])
    assert_equal Digest::SHA256.hexdigest(cert_der), manifest['cert_sha256'], 'the APK signing it opens it'
    assert_equal ApkExport.version_name(base_apk), manifest['min_app_version']
    refute_nil manifest['min_app_version']
    assert_equal BaseApk.backend_of(base_apk), manifest['backend']
    assert_equal false, manifest['compiled']
    assert_archive_contents(decrypt(archive, cert_der))
  end

  def test_an_apk_needs_a_choice_of_how_players_get_the_game
    base_apk = base_apk!
    keys = File.join(@dir, 'keys')
    assert_match(/--remote <manifest URL>.*--full/, export_refused('--apk', base_apk, '-k', keys))
    assert_match(/either --remote or --full/, export_refused('--apk', base_apk, '-k', keys, '--full',
                                                              '--remote', 'https://example.com/m.json'))
    assert_match(/absolute http/, export_refused('--apk', base_apk, '-k', keys, '--remote', 'example.com/m.json'))
    assert_match(/--debug-mode needs the game inside/,
                 export_refused('--apk', base_apk, '-k', keys, '--debug-mode', '--remote', 'https://example.com/m.json'))
  end

  def test_small_apk_from_a_game_archive
    base_apk = base_apk!
    FileUtils.mkdir_p(File.join(@project, 'Data/configs'))
    File.write(File.join(@project, 'Data/configs/infos_config.json'), '{"gameTitle": "Archived Game", "gameVersion": 42}')
    File.binwrite(File.join(@project, 'graphics/icons/game.png').tap { |p| FileUtils.mkdir_p(File.dirname(p)) }, ICON)
    File.binwrite(File.join(@project, 'Fonts/PokemonDS.ttf').tap { |p| FileUtils.mkdir_p(File.dirname(p)) }, 'font bytes')
    psa = File.join(@dir, 'game.psa')
    export('-o', psa)

    # Elsewhere than the project: everything comes from the archive
    elsewhere = File.join(@dir, 'elsewhere')
    FileUtils.mkdir_p(elsewhere)
    out_dir = File.join(@dir, 'out')
    output_path = File.join(out_dir, 'Archived.apk')
    keys = File.join(@dir, 'keys')
    output, status = Open3.capture2e({ 'PSDK_KS_PASS' => KEYSTORE_PASS }, RbConfig.ruby, EXPORT_ANDROID,
                                     '--from', psa, '-o', output_path, '--apk', base_apk, '-k', keys,
                                     '--remote', 'https://example.com/android-game.json', chdir: elsewhere)
    assert status.success?, output
    assert_match(/App name:\s+Archived Game/, output)
    manifest = JSON.parse(File.read(File.join(out_dir, RemoteGame::MANIFEST_NAME)))
    assert_equal 'Archived Game', manifest['title']
    assert_equal '42', manifest['version']
    assert_equal false, manifest['compiled']
    resources = ResourceTable.new(apk_entry(output_path, 'resources.arsc'))
    assert_equal ['Archived Game'], resources.string_values('app_name')
    resources.string_values('logo', type: 'drawable').each { |file| assert_equal ICON, apk_entry(output_path, file) }
    # The game's splash, shown while the game downloads
    assert_equal '{"gameTitle": "Archived Game", "gameVersion": 42}',
                 apk_entry(output_path, "#{SplashAssets::ASSET_DIR}/infos_config.json")
    assert_equal ICON, apk_entry(output_path, "#{SplashAssets::ASSET_DIR}/game.png")
    assert_equal 'font bytes', apk_entry(output_path, "#{SplashAssets::ASSET_DIR}/game_font.ttf")
    # The archive packed is the given one, encrypted for the APK's cert
    cert_der = Pkcs12Cert.signing_cert_der(File.join(keys, 'debug.p12'), KEYSTORE_PASS)
    assert_equal File.binread(psa), decrypt(File.join(out_dir, 'game.epsa'), cert_der)
  end

  def test_the_game_version_comes_from_the_project
    FileUtils.mkdir_p(File.join(@project, 'Data/configs'))
    File.write(File.join(@project, 'Data/configs/infos_config.json'), '{"gameTitle": "T", "gameVersion": 536}')
    assert_equal '536', RemoteGame.game_version(nil, @project)
    assert_equal '1.6.13', RemoteGame.game_version('1.6.13', @project)
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
    export('-o', output_path, '--apk', base_apk, '-k', keystore, '--full')
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
