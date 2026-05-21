# Extracts the splash's assets — project metadata, game icon, and a font —
# from the encrypted .epsa archive into <pwd>/loader_assets/. Runs as part
# of archive validation so loader.rb can later read these files directly
# off the filesystem, with no PhysFS mount in the splash interpreter.
#
# Font selection: Fonts/PokemonDS.ttf is preferred (it's PSDK's default);
# if it isn't in the archive, any *.ttf inside Fonts/ is used as a fallback.
# An archive with no font in Fonts/ is rejected — loader.rb's Text layer
# raises without a registered font, and we treat that as "archive invalid".

require 'fileutils'
require 'json'
require 'physfs'
require 'LiteRGSS'

INFOS_CONFIG_SRC = 'Data/configs/infos_config.json'.freeze
ICON_SRC         = 'graphics/icons/game.png'.freeze
PREFERRED_FONT   = 'Fonts/PokemonDS.ttf'.freeze
FONTS_DIR        = 'Fonts'.freeze
TARGET_DIR       = 'loader_assets'.freeze
TARGET_INFOS     = File.join(TARGET_DIR, 'infos_config.json').freeze
TARGET_ICON      = File.join(TARGET_DIR, 'game.png').freeze
TARGET_FONT      = File.join(TARGET_DIR, 'game_font.ttf').freeze
TARGET_COLOR     = File.join(TARGET_DIR, 'icon_color.json').freeze

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
raise "#{INFOS_CONFIG_SRC} missing from archive" unless PhysFS.exist?(INFOS_CONFIG_SRC)
raise "#{ICON_SRC} missing from archive"         unless PhysFS.exist?(ICON_SRC)

FileUtils.mkdir_p(TARGET_DIR)
icon_bytes = PhysFS.read(ICON_SRC)
File.binwrite(TARGET_INFOS, PhysFS.read(INFOS_CONFIG_SRC))
File.binwrite(TARGET_ICON,  icon_bytes)
File.binwrite(TARGET_FONT,  PhysFS.read(font_src))

# Best-effort: an icon that decodes to no opaque pixels (all-transparent
# PNG, or a decode failure) simply leaves icon_color.json absent —
# loader.rb falls back to its default indigo gradient in that case.
icon_color = detect_icon_color(icon_bytes)
if icon_color
  File.write(TARGET_COLOR, JSON.generate(r: icon_color[0], g: icon_color[1], b: icon_color[2]))
end

# Unmount so this validation-time mount does not leak into the compile
# interpreter, which mounts again from check_engine.rb. The PhysFS state
# is process-wide (statically linked Init_physfs), not per-interpreter,
# so without this the compile-side mount would stack a second copy.
PhysFS.unmount('archive.zip') rescue nil

puts "[extract_loader_assets] wrote #{TARGET_INFOS}, #{TARGET_ICON}, #{TARGET_FONT}" \
     " (font_src=#{font_src}, icon_color=#{icon_color&.inspect || 'unavailable'})"

ArchiveMount.unmount! # cleanup
