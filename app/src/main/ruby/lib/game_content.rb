# What a game archive holds, read through a PhysFS-like file system (any
# object answering exist?, directory?, read and enumerate; PhysFS itself on
# device):
#   - project sources (Game.rb, pokemonsdk/scripts...): compiled on device
#     by compile.rb;
#   - a compiled release (Game.yarb, Data/Scripts.dat, Data/<n>.dat, the
#     graphics packed as Yuki::VD files): installed as-is into Release/.
#
# Guarded like epsa_format.rb: this file is an ARCHIVE_PRELUDE, evaluated
# again by every script of the reused compile VM.
unless defined?(GameContent::COMPILED_GAME)

module GameContent
  module_function

  # PSDK's compilation writes it next to Game.rb, which only evaluates it.
  # Stock Studio releases may ship it alone.
  COMPILED_GAME = 'Game.yarb'.freeze
  RELEASE_GAME_RB = "RubyVM::InstructionSequence.load_from_binary(File.binread('Game.yarb')).eval".freeze
  # Where compile.rb mounts the archive alone to install a compiled release.
  INSTALL_MOUNT_POINT = '/release_install'.freeze

  # Splash metadata of a compiled release. Data/0.dat packs the
  # configs (see PSDK's ProjectCompilation add_data_files); the game icon
  # lives in the icon Yuki::VD of the project, else of PSDK.
  COMPILED_CONFIGS = 'Data/0.dat'.freeze
  COMPILED_INFOS   = 'infos_config.rxdata'.freeze
  COMPILED_ICONS   = %w[graphics/icon pokemonsdk/master/icon].freeze
  COMPILED_ICON    = 'game'.freeze

  # YARB header: magic, major, minor (u32 each), then the platform name
  # after the 36-byte header (Ruby 3.0-3.2; 3.3 has no platform).
  YARB_MAGIC = 'YARB'.b.freeze
  YARB_PLATFORM_OFFSET = 36

  def compiled_release?(fs)
    fs.exist?(COMPILED_GAME)
  end

  # Raises unless Game.yarb loads on this Ruby, with a message telling the
  # player which app variant runs it. Loading (not evaluating) runs the
  # same checks as the game's boot, without running any game code.
  def check_bytecode!(bin)
    raise "#{COMPILED_GAME} is not Ruby bytecode" unless bin.byteslice(0, 4) == YARB_MAGIC

    major, minor = bin.byteslice(4, 8).unpack('L<L<')
    ours = RUBY_VERSION.split('.').first(2).map(&:to_i)
    if [major, minor] != ours
      raise "This game was compiled by Ruby #{major}.#{minor}, this app runs Ruby #{ours.join('.')}: " \
            "install the PSDK-android-ruby#{major}.#{minor} app to play it"
    end

    begin
      RubyVM::InstructionSequence.load_from_binary(bin)
    rescue RuntimeError, TypeError, ArgumentError => e
      platform = bin.byteslice(YARB_PLATFORM_OFFSET, 64).to_s[/\A[^\0]*/]
      raise "#{COMPILED_GAME} (Ruby #{major}.#{minor}, #{platform}) does not load on this app: #{e.message}"
    end
  end

  # Entry of a Yuki::VD file: a u32 pointing at the Marshal'd name => offset
  # Hash, each entry being a u32 size followed by the data.
  def vd_read(vd, name)
    index = Marshal.load(vd.byteslice(vd.byteslice(0, 4).unpack1('L<')..))
    offset = index[name] or return nil
    vd.byteslice(offset + 4, vd.byteslice(offset, 4).unpack1('L<'))
  end

  # { gameTitle:, gameVersion: }, as in the infos_config.json of sources.
  def compiled_infos(fs)
    return nil unless fs.exist?(COMPILED_CONFIGS)

    data = vd_read(fs.read(COMPILED_CONFIGS), COMPILED_INFOS) or return nil
    infos = load_marshal(data)
    { gameTitle: infos.instance_variable_get(:@game_title),
      gameVersion: infos.instance_variable_get(:@game_version) }
  end

  def compiled_icon(fs)
    COMPILED_ICONS.each do |path|
      next unless fs.exist?(path)

      png = vd_read(fs.read(path), COMPILED_ICON)
      return png if png
    end
    nil
  end

  # Marshal.load of PSDK objects (Configs::Project::Infos...) without PSDK:
  # each missing class is defined as an empty stub for the load, then
  # removed, so the VM is left as it was (a later compile of sources
  # defines the real classes).
  def load_marshal(data)
    created = []
    begin
      Marshal.load(data)
    rescue ArgumentError => e
      name = e.message[%r{\Aundefined class/module ([\w:]+)}, 1]
      raise unless name && define_stub(name, created)

      retry
    end
  ensure
    created.reverse_each { |parent, const| parent.send(:remove_const, const) }
  end

  def define_stub(name, created)
    segments = name.split('::')
    parent = Object
    defined_any = false
    segments.each_with_index do |segment, i|
      if parent.const_defined?(segment, false)
        parent = parent.const_get(segment, false)
        next
      end
      stub = i == segments.size - 1 ? Class.new : Module.new
      parent.const_set(segment, stub)
      # Removing the outermost stub is enough to remove the ones inside.
      created << [parent, segment.to_sym] unless defined_any
      defined_any = true
      parent = stub
    end
    defined_any
  end

  # Copy every file under root of fs into dest (a real directory), then add
  # the Game.rb a release boots with if the archive has none.
  def install_release(fs, root, dest)
    count = copy_tree(fs, root, dest)
    game_rb = File.join(dest, 'Game.rb')
    File.write(game_rb, RELEASE_GAME_RB) unless File.exist?(game_rb)
    count
  end

  def copy_tree(fs, dir, dest)
    Dir.mkdir(dest) unless Dir.exist?(dest)
    fs.enumerate(dir).sum do |name|
      src = "#{dir.chomp('/')}/#{name}"
      target = File.join(dest, name)
      if fs.directory?(src)
        copy_tree(fs, src, target)
      else
        File.open(target, 'wb') { |f| f.write(fs.read(src)) }
        1
      end
    end
  end
end

end # unless defined?(GameContent::COMPILED_GAME)
