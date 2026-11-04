# Tests of lib/save_transfer.rb: the saves file, both ways, and what it
# never writes. The ZIPs made by other tools come from rubyzip (as the
# exporter's tests) and from the zip command when there is one.
#
# Run:  ruby exporter/test/test_save_transfer.rb

require 'minitest/autorun'
require 'fileutils'
require 'json'
require 'open3'
require 'rbconfig'
require 'tmpdir'
require 'zip'

require_relative '../lib/save_transfer'

class SaveTransferTest < Minitest::Test
  SCRIPT = File.expand_path('../lib/save_transfer.rb', __dir__)
  NOW = Time.utc(2026, 11, 4, 12, 30, 0)

  def setup
    @dir = Dir.mktmpdir('save_transfer_test')
    @game = File.join(@dir, 'game')
    write(@game, 'Data/configs/infos_config.json' => '{"gameTitle": "Test Game"}',
                 'Saves/Pokemon_Party' => "PKPRT\x00\xff".b,
                 'Saves/Pokemon_Party.bak' => 'old',
                 'Saves/Pokemon_Party-2' => 'second',
                 'Saves/input.json' => '{"keys": 1}',
                 'Saves/Telemetry/id' => 'abc',
                 'GlobalPokedex.dat' => 'dex',
                 'GlobalPokedex.dat.bak' => 'dex-old',
                 'deleted_saves/Pokemon_Party-3' => 'gone',
                 'battle.dat' => 'game data',
                 '.gameopts' => '--scale=2',
                 'Game.rb' => 'puts 1')
  end

  def teardown
    FileUtils.rm_rf(@dir)
  end

  def write(root, files)
    files.each do |path, data|
      FileUtils.mkdir_p(File.dirname(File.join(root, path)))
      File.binwrite(File.join(root, path), data)
    end
  end

  def read_tree(root)
    Dir.glob('**/*', File::FNM_DOTMATCH, base: root).select { |p| File.file?(File.join(root, p)) }.sort
       .to_h { |p| [p, File.binread(File.join(root, p))] }
  end

  SAVES = %w[GlobalPokedex.dat GlobalPokedex.dat.bak Saves/Pokemon_Party Saves/Pokemon_Party-2
             Saves/Pokemon_Party.bak deleted_saves/Pokemon_Party-3].freeze

  def test_transferable
    SAVES.each { |path| assert SaveTransfer.transferable?(path), path }
    ['Saves/input.json', 'Saves/input.yml', 'Saves/Telemetry/id', 'battle.dat', '.gameopts', 'Game.rb', 'Data/configs/x.json', 'Saves', 'Saves/',
     '../Saves/x', 'Saves/../Game.rb', '/Saves/x', 'Saves//x', 'Saves/./x', 'Saves\\x', 'C:/Saves/x',
     'SavesBackup/1/Saves/x', 'GlobalPokedex.dat/x', 'sub/GlobalPokedex.dat', '__MACOSX/Saves/._x',
     'psdk_saves.json', 'README.txt'].each { |path| refute SaveTransfer.transferable?(path), path }
  end

  def test_export_writes_the_saves_and_a_manifest
    file = File.join(@dir, 'out', 'saves.zip')
    assert_equal SAVES, SaveTransfer.export(@game, file, game: 'Test Game', now: NOW)

    Zip::File.open(file) do |zip|
      assert_equal (['psdk_saves.json', 'README.txt'] + SAVES).sort, zip.entries.map(&:name).sort
      manifest = JSON.parse(zip.read('psdk_saves.json'))
      assert_equal({ 'format' => 1, 'game' => 'Test Game', 'platform' => 'desktop',
                     'exported_at' => '2026-11-04T12:30:00Z', 'files' => SAVES }, manifest)
      assert_equal "PKPRT\x00\xff".b, zip.read('Saves/Pokemon_Party').b
    end
    refute File.exist?("#{file}.tmp")
  end

  def test_export_without_saves_fails
    empty = File.join(@dir, 'empty')
    FileUtils.mkdir_p(empty)
    assert_raises(SaveTransfer::Error) { SaveTransfer.export(empty, File.join(@dir, 'x.zip'), game: 'x') }
    refute File.exist?(File.join(@dir, 'x.zip'))
  end

  def test_import_replaces_the_saves_and_keeps_the_others_aside
    file = File.join(@dir, 'saves.zip')
    SaveTransfer.export(@game, file, game: 'Test Game', now: NOW)
    other = File.join(@dir, 'other')
    write(other, 'Saves/Pokemon_Party-1' => 'theirs', 'Saves/input.json' => 'their keys',
                 'GlobalPokedex.dat' => 'their dex', 'Game.rb' => 'their game')

    saves, backup = SaveTransfer.import(file, other, now: NOW)

    assert_equal SAVES, saves.sort
    assert_equal File.join(other, 'SavesBackup', '20261104-123000'), backup
    tree = read_tree(other)
    SAVES.each { |path| assert_equal File.binread(File.join(@game, path)), tree[path], path }
    refute tree.key?('Saves/Pokemon_Party-1'), 'the saves in place are replaced'
    assert_equal 'their keys', tree['Saves/input.json'], 'the keys stay'
    assert_equal 'their game', tree['Game.rb']
    assert_equal 'theirs', tree['SavesBackup/20261104-123000/Saves/Pokemon_Party-1']
    assert_equal 'their dex', tree['SavesBackup/20261104-123000/GlobalPokedex.dat']
    refute File.exist?(File.join(other, '.saves_import'))
  end

  def test_import_into_an_empty_folder_has_no_backup
    file = File.join(@dir, 'saves.zip')
    SaveTransfer.export(@game, file, game: 'Test Game')
    target = File.join(@dir, 'new')
    _, backup = SaveTransfer.import(file, target)
    assert_nil backup
    assert_equal SAVES, SaveTransfer.files(target)
  end

  # A ZIP made by hand (rubyzip, stored and deflated, unsafe names): only the
  # saves, inside the root
  def test_import_reads_a_zip_made_by_hand_and_ignores_the_rest
    file = File.join(@dir, 'hand.zip')
    Zip::OutputStream.open(file) do |zip|
      zip.put_next_entry('Saves/Pokemon_Party', nil, nil, Zip::Entry::STORED)
      zip.write 'stored save'
      zip.put_next_entry('Saves/Pokemon_Party-1')
      zip.write 'deflated save' * 100
      zip.put_next_entry('Game.rb')
      zip.write 'evil'
      zip.put_next_entry('__MACOSX/Saves/._Pokemon_Party')
      zip.write 'mac'
    end
    # rubyzip refuses to write a ../ name: patch one in, same length
    data = File.binread(file).gsub('Game.rb'.b, '../x.rb'.b)
    File.binwrite(file, data)

    target = File.join(@dir, 'target')
    write(target, 'Game.rb' => 'mine')
    info = SaveTransfer.inspect_file(file)
    assert_equal %w[Saves/Pokemon_Party Saves/Pokemon_Party-1], info['saves']
    assert_nil info['game']

    SaveTransfer.import(file, target)
    assert_equal({ 'Game.rb' => 'mine', 'Saves/Pokemon_Party' => 'stored save',
                   'Saves/Pokemon_Party-1' => 'deflated save' * 100 }, read_tree(target))
    refute File.exist?(File.join(@dir, 'x.rb'))
  end

  # fixtures/android-saves.zip: exported by the app (java.util.zip: data
  # descriptors after each entry), dummy saves
  def test_import_of_a_saves_file_of_the_app
    file = File.expand_path('fixtures/android-saves.zip', __dir__)
    info = SaveTransfer.inspect_file(file)
    assert_equal 'ForetEternelle', info['game']
    assert_equal 'android', info['platform']
    assert_equal %w[GlobalPokedex.dat Saves/Pokemon_Party-1], info['saves']

    target = File.join(@dir, 'target')
    SaveTransfer.import(file, target)
    assert_equal({ 'GlobalPokedex.dat' => 'dex', 'Saves/Pokemon_Party-1' => 'PKPRT save' }, read_tree(target))
  end

  def test_import_of_the_zip_command_output
    skip 'no zip command' unless system('zip', '-v', out: File::NULL, err: File::NULL)

    file = File.join(@dir, 'cli.zip')
    _, status = Open3.capture2e('zip', '-qr', file, 'Saves', 'GlobalPokedex.dat', chdir: @game)
    assert status.success?
    target = File.join(@dir, 'target')
    saves, = SaveTransfer.import(file, target)
    assert_includes saves, 'Saves/Pokemon_Party'
    assert_equal File.binread(File.join(@game, 'Saves/Pokemon_Party')), File.binread(File.join(target, 'Saves/Pokemon_Party'))
  end

  def test_a_file_without_saves_changes_nothing
    file = File.join(@dir, 'nothing.zip')
    Zip::File.open(file, create: true) { |zip| zip.get_output_stream('Game.rb') { |io| io.write 'x' } }
    before = read_tree(@game)
    assert_raises(SaveTransfer::Error) { SaveTransfer.import(file, @game) }
    assert_equal before, read_tree(@game)
  end

  def test_a_damaged_file_changes_nothing
    file = File.join(@dir, 'saves.zip')
    SaveTransfer.export(@game, file, game: 'Test Game')
    data = File.binread(file)
    # Flip a byte of an entry's data (right after its local header's name):
    # its CRC no longer matches
    offset = data.index('Saves/Pokemon_Party-2'.b) + 'Saves/Pokemon_Party-2'.bytesize + 1
    data.setbyte(offset, data.getbyte(offset) ^ 0xff)
    File.binwrite(file, data)

    target = File.join(@dir, 'target')
    write(target, 'Saves/Pokemon_Party' => 'mine')
    assert_raises(SaveTransfer::Error) { SaveTransfer.import(file, target) }
    assert_equal({ 'Saves/Pokemon_Party' => 'mine' }, read_tree(target))

    File.binwrite(file, 'not a zip')
    assert_raises(SaveTransfer::Error) { SaveTransfer.inspect_file(file) }
  end

  def zip_of(file, entries)
    Zip::OutputStream.open(file) do |zip|
      entries.each do |name, data|
        zip.put_next_entry(name)
        zip.write data
      end
    end
    file
  end

  def test_a_name_given_twice_is_the_first_one
    file = File.join(@dir, 'twice.zip')
    File.open(file, 'wb') { |io| SaveTransfer::Zip.write(io, [%w[Saves/Pokemon_Party first], %w[Saves/Pokemon_Party second]], NOW) }
    target = File.join(@dir, 'target')
    saves, = SaveTransfer.import(file, target)
    assert_equal %w[Saves/Pokemon_Party], saves
    assert_equal 'first', File.read(File.join(target, 'Saves/Pokemon_Party'))
  end

  def test_two_imports_in_the_same_second_keep_both_backups
    backup_root = File.join(@dir, 'SavesBackup')
    _, first = SaveTransfer.import(zip_of(File.join(@dir, '1.zip'), [%w[Saves/Pokemon_Party 1]]), @game, backup_root: backup_root, now: NOW)
    _, second = SaveTransfer.import(zip_of(File.join(@dir, '2.zip'), [%w[Saves/Pokemon_Party 2]]), @game, backup_root: backup_root, now: NOW)
    assert_equal File.join(backup_root, '20261104-123000'), first
    assert_equal File.join(backup_root, '20261104-123000-2'), second
    assert_equal 'second', File.read(File.join(first, 'Saves/Pokemon_Party-2'))
    assert_equal '1', File.read(File.join(second, 'Saves/Pokemon_Party'))
  end

  # A move that fails (a folder where a save goes): everything back as before
  def test_a_failed_import_puts_everything_back
    target = File.join(@dir, 'target')
    write(target, 'Saves/Pokemon_Party/x' => "a folder's file", 'Saves/Pokemon_Party-1' => 'mine')
    before = read_tree(target)
    file = zip_of(File.join(@dir, 'f.zip'), [%w[Saves/A a], %w[Saves/Pokemon_Party theirs]])
    error = assert_raises(SaveTransfer::Error) { SaveTransfer.import(file, target, backup_root: File.join(@dir, 'b')) }
    assert_match(/nothing changed/, error.message)
    assert_equal before, read_tree(target)
  end

  def test_a_name_flagged_utf8_that_is_not
    file = File.join(@dir, 'bad-name.zip')
    File.open(file, 'wb') { |io| SaveTransfer::Zip.write(io, [['Saves/Pokemon_Party', 'save'], ["Saves/\xff\xfe".dup.force_encoding(Encoding::UTF_8), 'x']], NOW) }
    # Refused as the app refuses it (ZipInputStream can't skip the entry)
    assert_raises(SaveTransfer::Error) { SaveTransfer.inspect_file(file) }
    target = File.join(@dir, 'target')
    write(target, 'Saves/Pokemon_Party' => 'mine')
    assert_raises(SaveTransfer::Error) { SaveTransfer.import(file, target) }
    assert_equal({ 'Saves/Pokemon_Party' => 'mine' }, read_tree(target))
  end

  def test_a_newer_format_is_refused
    file = File.join(@dir, 'future.zip')
    Zip::File.open(file, create: true) do |zip|
      zip.get_output_stream('psdk_saves.json') { |io| io.write '{"format": 2}' }
      zip.get_output_stream('Saves/Pokemon_Party') { |io| io.write 'x' }
    end
    assert_raises(SaveTransfer::Error) { SaveTransfer.inspect_file(file) }
  end

  def test_save_root_follows_psdk
    assert_equal File.expand_path(@game), SaveTransfer.save_root(@game, 'Test Game')
    assert_equal 'Test Game', SaveTransfer.game_title(@game)
    project = File.join(@dir, 'project')
    write(project, 'project.studio' => '{"title": "From Studio"}')
    assert_equal 'From Studio', SaveTransfer.game_title(project)
  end

  def test_command_line_both_ways
    file = File.join(@dir, 'cli-saves.zip')
    out, status = Open3.capture2e(RbConfig.ruby, SCRIPT, 'export', file, chdir: @game)
    assert status.success?, out
    assert_match(/6 save files/, out)

    target = File.join(@dir, 'target')
    write(target, 'Data/configs/infos_config.json' => '{"gameTitle": "Another Game"}', 'Saves/Pokemon_Party' => 'mine')
    out, status = Open3.capture2e(RbConfig.ruby, SCRIPT, 'import', file, chdir: target)
    assert status.success?, out
    assert_match(/saves of Test Game from desktop/, out)
    assert_match(/It is not Another Game's/, out)
    assert_match(/SavesBackup/, out)
    assert_equal "PKPRT\x00\xff".b, File.binread(File.join(target, 'Saves/Pokemon_Party'))

    out, status = Open3.capture2e(RbConfig.ruby, SCRIPT, 'import', chdir: target)
    refute status.success?
    assert_match(/needs the saves file/, out)
    _, status = Open3.capture2e(RbConfig.ruby, SCRIPT, '--help', chdir: target)
    assert status.success?
  end

  def test_exporter_flags
    exporter = File.expand_path('../export_android.rb', __dir__)
    file = File.join(@dir, 'flag-saves.zip')
    out, status = Open3.capture2e(RbConfig.ruby, exporter, '--export-saves', file, chdir: @game)
    assert status.success?, out
    target = File.join(@dir, 'target')
    FileUtils.mkdir_p(target)
    out, status = Open3.capture2e(RbConfig.ruby, exporter, '--import-saves', file, '--save-root', target, chdir: @game)
    assert status.success?, out
    assert_equal SAVES, SaveTransfer.files(target)

    out, status = Open3.capture2e(RbConfig.ruby, exporter, '--export-saves', chdir: @game)
    assert status.success?, out
    assert File.file?(File.join(@game, "Test_Game-saves-#{Time.now.strftime('%Y%m%d')}.zip"))
  end
end
