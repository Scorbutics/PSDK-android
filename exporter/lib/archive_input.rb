# A game archive as the export's input (export_android.rb --from): a plain
# game archive (.psa, a ZIP) built earlier, by this exporter (-o game.psa) or
# from a game's folder, project sources or a compiled release. The export
# packs it as is (encrypted for the APK's cert unless --archive psa) instead
# of building it from a project, and reads what a project would give from
# it: the game's title, version and icon, and whether it's compiled (its
# bytecode's Ruby then picks the base APK).
#
# The reading is the app's (app/src/main/ruby/lib/game_content.rb, as the
# splash reads an imported archive): a compiled release packs its configs and
# icon in Yuki::VD files, read without PSDK.
require 'json'
require 'zip'
require_relative 'game_content'

class ArchiveInput
  class Error < StandardError; end

  # Sources' configs and icons (a compiled release: GameContent)
  INFOS_CONFIG = 'Data/configs/infos_config.json'.freeze
  ICONS = %w[studio_icon.png graphics/icons/game.png].freeze

  attr_reader :path

  def initialize(path)
    @path = path
    raise Error, "Archive not found: #{path}" unless File.file?(path)

    @zip = Zip::File.open(path)
  rescue Zip::Error => e
    raise Error, "#{path} is not a game archive (a ZIP): #{e.message}"
  end

  def close
    @zip.close
  end

  # The file system GameContent reads: exist?, directory?, read, enumerate
  def exist?(name)
    !@zip.find_entry(name).nil? || directory?(name)
  end

  def directory?(name)
    prefix = "#{name.chomp('/')}/"
    @zip.entries.any? { |entry| entry.name.start_with?(prefix) }
  end

  def read(name)
    entry = @zip.find_entry(name) or raise Error, "#{name} not in #{path}"
    entry.get_input_stream(&:read).b
  end

  def enumerate(dir)
    prefix = dir.empty? ? '' : "#{dir.chomp('/')}/"
    @zip.entries.filter_map { |entry| entry.name.delete_prefix(prefix).split('/').first if entry.name.start_with?(prefix) }.uniq
  end

  def compiled?
    GameContent.compiled_release?(self)
  end

  # Of a game the app can play: sources (Game.rb) or a compiled release
  def check!
    return if compiled? || exist?('Game.rb')

    raise Error, "#{path} holds no game: neither Game.rb (project sources) nor Game.yarb (a compiled release)"
  end

  # The Ruby that compiled Game.yarb ("3.0"), nil for sources
  def bytecode_ruby
    return nil unless compiled?

    yarb = read(GameContent::COMPILED_GAME)
    raise Error, "#{path}: Game.yarb is not Ruby bytecode" unless yarb.start_with?('YARB')

    yarb.byteslice(4, 8).unpack('L<L<').join('.')
  end

  # { 'gameTitle' => ..., 'gameVersion' => ... }, or {}
  def infos
    @infos ||= if compiled?
                 (GameContent.compiled_infos(self) || {}).transform_keys(&:to_s)
               elsif exist?(INFOS_CONFIG)
                 JSON.parse(read(INFOS_CONFIG))
               else
                 {}
               end
  rescue JSON::ParserError, StandardError => e
    warn "  #{path}: game infos not read (#{e.class}: #{e.message})"
    @infos = {}
  end

  def title
    title = infos['gameTitle']
    title.is_a?(String) && !title.strip.empty? ? title : nil
  end

  def version
    infos['gameVersion']&.to_s
  end

  # The game's icon (PNG bytes), nil if none
  def icon_png
    return GameContent.compiled_icon(self) if compiled?

    ICONS.each { |name| return read(name) if @zip.find_entry(name) }
    nil
  end
end
