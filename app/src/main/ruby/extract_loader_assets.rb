# Extracts the splash's assets — project metadata, game icon, and a font —
# from the game archive into <pwd>/loader_assets/. Runs as part
# of archive validation so loader.rb can later read these files directly
# off the filesystem, with no PhysFS mount in the splash interpreter.
#
# Font selection: Fonts/PokemonDS.ttf is preferred (it's PSDK's default);
# if it isn't in the archive, any *.ttf inside Fonts/ is used as a fallback.
# An archive with no font in Fonts/ is rejected — loader.rb's Text layer
# raises without a registered font, and we treat that as "archive invalid".
#
# A compiled release (GameContent.compiled_release?) has no
# infos_config.json nor game.png: its configs and graphics are packed, so
# the metadata is read from Data/0.dat and the icon from its icon Yuki::VD.
# Its Game.yarb must also load on this app's Ruby: a game compiled by
# another Ruby is refused here, at import, instead of at its first launch.
# TARGET_COMPILED marks it for the app: it has no sources to run in Debug.

require 'fileutils'
require 'json'
require 'physfs'
require 'LiteRGSS'

INFOS_CONFIG_SRC = 'Data/configs/infos_config.json'.freeze
# First one present wins. studio_icon.png is the project icon from
# project.studio (export_android.rb packs it; e.g. 154x154): much sharper
# than PSDK's 32x32 game icon, which older archives fall back to.
ICON_SOURCES     = %w[studio_icon.png graphics/icons/game.png].freeze
PREFERRED_FONT   = 'Fonts/PokemonDS.ttf'.freeze
FONTS_DIR        = 'Fonts'.freeze
TARGET_DIR       = 'loader_assets'.freeze
TARGET_INFOS     = File.join(TARGET_DIR, 'infos_config.json').freeze
TARGET_ICON      = File.join(TARGET_DIR, 'game.png').freeze
TARGET_FONT      = File.join(TARGET_DIR, 'game_font.ttf').freeze
TARGET_COLOR     = File.join(TARGET_DIR, 'icon_color.json').freeze
TARGET_COMPILED  = File.join(TARGET_DIR, 'compiled_release').freeze
# Version of what this script writes: the app re-extracts loader_assets
# written by another one (ArchiveValidator.LOADER_ASSETS_VERSION, keep both
# in sync when the written files change).
TARGET_VERSION   = File.join(TARGET_DIR, 'extract_version').freeze
EXTRACT_VERSION  = '2'.freeze

ArchiveMount.mount!

def pick_font_source
  return PREFERRED_FONT if PhysFS.exist?(PREFERRED_FONT)
  raise "Archive has no Fonts/ directory" unless PhysFS.directory?(FONTS_DIR)
  fallback = PhysFS.enumerate(FONTS_DIR).find { |name| name =~ /\.ttf\z/i }
  raise "Archive Fonts/ has no .ttf font file" unless fallback
  File.join(FONTS_DIR, fallback)
end

# Per-channel median of the icon's opaque pixels. Drives loader.rb's
# background gradient so the splash feels themed to the project.
#
# - Transparent / near-transparent pixels (alpha < 128) are skipped so
#   an icon's transparent border can't pull the median toward black.
# - Sampled on a grid sized so any icon dimension produces ≤ ~64 samples
#   along its edge — for a typical 64×64 icon this is a full scan; for a
#   256×256 icon it's still ~4000 samples, plenty to nail a median.
# - Returns nil if the icon decoded to zero opaque pixels; callers
#   should treat that as "no detection available" and skip the write.
def detect_icon_color(png_data)
  img = LiteRGSS::Image.new(png_data, true)
  w = img.width
  h = img.height
  step = [[w, h].max / 64, 1].max
  rs = []
  gs = []
  bs = []
  (0...h).step(step) do |y|
    (0...w).step(step) do |x|
      c = img.get_pixel(x, y)
      next if c.alpha < 128
      rs << c.red
      gs << c.green
      bs << c.blue
    end
  end
  img.dispose
  return nil if rs.empty?
  rs.sort!
  gs.sort!
  bs.sort!
  mid = rs.length / 2
  [rs[mid], gs[mid], bs[mid]]
end

font_src = pick_font_source
compiled = GameContent.compiled_release?(PhysFS)
if compiled
  GameContent.check_bytecode!(PhysFS.read(GameContent::COMPILED_GAME))
  infos = GameContent.compiled_infos(PhysFS)
  raise "#{GameContent::COMPILED_INFOS} missing from #{GameContent::COMPILED_CONFIGS}" unless infos
  infos_json = JSON.generate(infos)
  icon_src = 'studio_icon.png' if PhysFS.exist?('studio_icon.png')
  icon_bytes = icon_src ? PhysFS.read(icon_src) : GameContent.compiled_icon(PhysFS)
  raise "#{GameContent::COMPILED_ICON} icon missing from #{GameContent::COMPILED_ICONS.join(', ')}" unless icon_bytes
  icon_src ||= GameContent::COMPILED_ICONS.join('|')
else
  raise "#{INFOS_CONFIG_SRC} missing from archive" unless PhysFS.exist?(INFOS_CONFIG_SRC)
  icon_src = ICON_SOURCES.find { |path| PhysFS.exist?(path) }
  raise "#{ICON_SOURCES.last} missing from archive" unless icon_src
  infos_json = PhysFS.read(INFOS_CONFIG_SRC)
  icon_bytes = PhysFS.read(icon_src)
end

FileUtils.mkdir_p(TARGET_DIR)
File.binwrite(TARGET_INFOS, infos_json)
File.binwrite(TARGET_ICON,  icon_bytes)
File.binwrite(TARGET_FONT,  PhysFS.read(font_src))
# Removed otherwise: the directory may hold a previous import's marker
compiled ? File.write(TARGET_COMPILED, '') : FileUtils.rm_f(TARGET_COMPILED)
File.write(TARGET_VERSION, EXTRACT_VERSION)

# Best-effort: an icon that decodes to no opaque pixels (all-transparent
# PNG, or a decode failure) simply leaves icon_color.json absent —
# loader.rb falls back to its default indigo gradient in that case.
icon_color = detect_icon_color(icon_bytes)
if icon_color
  File.write(TARGET_COLOR, JSON.generate(r: icon_color[0], g: icon_color[1], b: icon_color[2]))
end

puts "[extract_loader_assets] wrote #{TARGET_INFOS}, #{TARGET_ICON}, #{TARGET_FONT}" \
     " (font_src=#{font_src}, icon_src=#{icon_src}, icon_color=#{icon_color&.inspect || 'unavailable'})"

# Unmount so this validation-time mount does not leak into the compile
# interpreter, which mounts again from check_engine.rb. The PhysFS state
# is process-wide (statically linked Init_physfs), not per-interpreter,
# so without this the compile-side mount would stack a second copy.
ArchiveMount.unmount!
