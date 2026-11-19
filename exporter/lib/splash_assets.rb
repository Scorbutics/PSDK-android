# frozen_string_literal: true

require 'json'
require_relative 'game_content'

# A small APK's splash before its game is downloaded: the game's title and
# version, icon and font, as the app's extract_loader_assets.rb writes them
# from the archive after the download (loader_assets/: infos_config.json,
# game.png, game_font.ttf). Packed in the APK under ASSET_DIR, the app shows
# the game's own splash from the first launch: its icon rolling while the
# game downloads.
#
# Read from what GameContent reads (exist?, directory?, read, enumerate): an
# ArchiveInput, or a project folder (Folder).
module SplashAssets
  ASSET_DIR = 'assets/loader_assets_game'
  INFOS_CONFIG = 'Data/configs/infos_config.json'
  # extract_loader_assets.rb's: studio_icon.png first (Studio's, sharper)
  ICONS = %w[studio_icon.png graphics/icons/game.png].freeze
  PREFERRED_FONT = 'Fonts/PokemonDS.ttf'
  FONTS_DIR = 'Fonts'

  # A project folder, read as an archive
  class Folder
    def initialize(root)
      @root = root
    end

    def exist?(name) = File.exist?(path(name))
    def directory?(name) = File.directory?(path(name))
    def read(name) = File.binread(path(name))
    def enumerate(dir) = Dir.children(path(dir))

    private

    def path(name) = File.join(@root, name)
  end

  module_function

  # { APK entry name => bytes }, or {} with a warning when the game lacks
  # one of them (the app then shows its own splash while downloading).
  # @param icon [String, nil] PNG bytes overriding the game's icon (Studio's)
  def entries(fs, icon: nil)
    infos = infos_json(fs) or return missing('its title and version (game infos)')
    icon ||= icon_png(fs) or return missing('its icon')
    font = font_path(fs) or return missing("a font (#{FONTS_DIR}/*.ttf)")
    {
      "#{ASSET_DIR}/infos_config.json" => infos,
      "#{ASSET_DIR}/game.png" => icon,
      "#{ASSET_DIR}/game_font.ttf" => fs.read(font)
    }
  end

  def infos_json(fs)
    if GameContent.compiled_release?(fs)
      infos = GameContent.compiled_infos(fs)
      infos && JSON.generate(infos)
    elsif fs.exist?(INFOS_CONFIG)
      fs.read(INFOS_CONFIG)
    end
  end

  def icon_png(fs)
    return GameContent.compiled_icon(fs) if GameContent.compiled_release?(fs)

    name = ICONS.find { |icon| fs.exist?(icon) }
    name && fs.read(name)
  end

  def font_path(fs)
    return PREFERRED_FONT if fs.exist?(PREFERRED_FONT)
    return nil unless fs.directory?(FONTS_DIR)

    name = fs.enumerate(FONTS_DIR).sort.find { |file| file =~ /\.ttf\z/i }
    name && "#{FONTS_DIR}/#{name}"
  end

  def missing(what)
    warn "  The game's splash is not in the APK: no #{what}. The app shows its own while the game downloads."
    {}
  end
end
