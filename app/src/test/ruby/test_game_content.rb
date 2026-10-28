# Unit tests for GameContent — compiled release detection, splash metadata
# of a compiled release, bytecode check and install.
#
# On device GameContent reads the archive through PhysFS; here DirFS serves
# a temp directory through the same four methods.
#
# Run:  ruby app/src/test/ruby/test_game_content.rb

require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'

LIB = File.expand_path('../../main/ruby/lib', __dir__)
require File.join(LIB, 'game_content')

# PhysFS-like view of a real directory: paths relative to root, with or
# without a leading slash.
class DirFS
  def initialize(root)
    @root = root
  end

  def exist?(path) = File.exist?(real(path))
  def directory?(path) = File.directory?(real(path))
  def read(path) = File.binread(real(path))
  def enumerate(path) = Dir.children(real(path)).sort

  private

  def real(path) = File.join(@root, path.delete_prefix('/'))
end

module VDBuilder
  module_function

  # Yuki::VD layout: u32 index offset, entries (u32 size + data), Marshal'd index.
  def build(entries)
    body = ''.b
    index = {}
    entries.each do |name, data|
      index[name] = 4 + body.bytesize
      body << [data.bytesize].pack('L<') << data.b
    end
    [4 + body.bytesize].pack('L<') + body + Marshal.dump(index)
  end
end

class TestGameContent < Minitest::Test
  def setup
    @dir = Dir.mktmpdir('game_content')
    @fs = DirFS.new(@dir)
  end

  def teardown
    FileUtils.rm_rf(@dir)
  end

  def write(path, data)
    full = File.join(@dir, path)
    FileUtils.mkdir_p(File.dirname(full))
    File.binwrite(full, data)
  end

  # Marshal of a Configs::Project::Infos as PSDK's compilation writes it,
  # made with throwaway classes removed right after.
  def infos_rxdata(title, version)
    Object.const_set(:Configs, Module.new)
    Configs.const_set(:Project, Module.new)
    Configs::Project.const_set(:Infos, Class.new)
    infos = Configs::Project::Infos.new
    infos.instance_variable_set(:@game_title, title)
    infos.instance_variable_set(:@game_version, version)
    Marshal.dump(infos)
  ensure
    Object.send(:remove_const, :Configs)
  end

  def test_sources_are_not_a_compiled_release
    write('Game.rb', 'puts 1')
    refute GameContent.compiled_release?(@fs)
  end

  def test_game_yarb_is_a_compiled_release
    write('Game.yarb', 'YARB')
    assert GameContent.compiled_release?(@fs)
  end

  def test_compiled_infos_reads_data_0_without_psdk_classes
    write('Data/0.dat', VDBuilder.build('actors.rxdata' => 'x',
                                        'infos_config.rxdata' => infos_rxdata('Forêt Éternelle', 256)))
    assert_equal({ gameTitle: 'Forêt Éternelle', gameVersion: 256 }, GameContent.compiled_infos(@fs))
    refute defined?(Configs), 'the stub classes must be removed after the load'
  end

  def test_compiled_infos_keeps_existing_classes
    write('Data/0.dat', VDBuilder.build('infos_config.rxdata' => infos_rxdata('Game', 1)))
    configs = Object.const_set(:Configs, Module.new)
    assert_equal 'Game', GameContent.compiled_infos(@fs)[:gameTitle]
    assert_same configs, Configs
    refute configs.const_defined?(:Project, false)
  ensure
    Object.send(:remove_const, :Configs) if defined?(Configs)
  end

  def test_compiled_infos_nil_without_configs
    assert_nil GameContent.compiled_infos(@fs)
    write('Data/0.dat', VDBuilder.build('actors.rxdata' => 'x'))
    assert_nil GameContent.compiled_infos(@fs)
  end

  def test_compiled_icon_prefers_the_project_icon
    write('pokemonsdk/master/icon', VDBuilder.build('game' => 'psdk png'))
    assert_equal 'psdk png', GameContent.compiled_icon(@fs)
    write('graphics/icon', VDBuilder.build('001' => 'other', 'game' => 'project png'))
    assert_equal 'project png', GameContent.compiled_icon(@fs)
  end

  def test_check_bytecode_loads_native_bytecode
    bin = RubyVM::InstructionSequence.compile('1 + 1').to_binary
    assert_equal 2, GameContent.check_bytecode!(bin).eval
  end

  def test_check_bytecode_names_the_variant_to_install
    bin = RubyVM::InstructionSequence.compile('1').to_binary
    major, minor = RUBY_VERSION.split('.').map(&:to_i)
    bin[4, 8] = [major, minor + 1].pack('L<L<')
    error = assert_raises(RuntimeError) { GameContent.check_bytecode!(bin) }
    assert_includes error.message, "install the PSDK-android-ruby#{major}.#{minor + 1} app"
  end

  def test_check_bytecode_refuses_other_files
    error = assert_raises(RuntimeError) { GameContent.check_bytecode!('PK\x03\x04 not bytecode') }
    assert_includes error.message, 'is not Ruby bytecode'
  end

  def test_install_release_copies_everything_and_adds_game_rb
    write('Game.yarb', 'bytecode')
    write('Data/Scripts.dat', 'scripts')
    write('audio/bgm/a.ogg', 'ogg')
    FileUtils.mkdir_p(File.join(@dir, 'Saves'))
    dest = File.join(Dir.mktmpdir('release'), 'Release')

    assert_equal 3, GameContent.install_release(@fs, '/', dest)
    assert_equal 'scripts', File.binread(File.join(dest, 'Data/Scripts.dat'))
    assert_equal 'ogg', File.binread(File.join(dest, 'audio/bgm/a.ogg'))
    assert File.directory?(File.join(dest, 'Saves'))
    assert_equal GameContent::RELEASE_GAME_RB, File.read(File.join(dest, 'Game.rb'))
  ensure
    FileUtils.rm_rf(File.dirname(dest)) if dest
  end

  def test_install_release_keeps_the_archive_game_rb
    write('Game.yarb', 'bytecode')
    write('Game.rb', 'custom')
    dest = File.join(Dir.mktmpdir('release'), 'Release')
    GameContent.install_release(@fs, '/', dest)
    assert_equal 'custom', File.read(File.join(dest, 'Game.rb'))
  ensure
    FileUtils.rm_rf(File.dirname(dest)) if dest
  end
end
