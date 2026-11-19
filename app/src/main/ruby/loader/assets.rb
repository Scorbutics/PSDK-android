require 'json'

unless defined?(Loader::Assets::DEFAULT_FONT_PATH)

module Loader
  # Reads project metadata, icon color, icon bitmap, and the splash
  # font from <pwd>/loader_assets/ — files extract_loader_assets.rb
  # wrote during archive validation. Zero archive dependency: no
  # PhysFS, no ArchiveMount, no EpsaStream.
  module Assets
    # Absolute paths anchored at the loader's startup cwd — see
    # LOADER_BASE_DIR in util.rb for the rationale. Today these are only
    # read in Splash#show! (before any sibling interpreter has a chance
    # to chdir the process), so relative paths happen to work; absolute
    # paths just make that contract explicit and survive future changes
    # that might re-read them later.
    DEFAULT_FONT_PATH = "#{LOADER_BASE_DIR}/loader_assets/game_font.ttf".freeze
    ICON_PATH         = "#{LOADER_BASE_DIR}/loader_assets/game.png".freeze
    INFOS_CONFIG_PATH = "#{LOADER_BASE_DIR}/loader_assets/infos_config.json".freeze
    ICON_COLOR_PATH   = "#{LOADER_BASE_DIR}/loader_assets/icon_color.json".freeze
    # Written by the host (LoaderState.Preparing) while it prepares a
    # project the splash can't show yet, e.g. the classic wizard copying
    # the bundled archive. Holds the one-line message to display.
    PREPARING_PATH    = "#{LOADER_BASE_DIR}/loader_assets/preparing.txt".freeze
    # Font ids from here on are the splash's font at one size each (#font_id)
    FIRST_SIZED_FONT_ID = 10

    # Fallback gradient hue when extract_loader_assets.rb didn't write
    # icon_color.json (e.g. all-transparent icon, decode failure).
    # Deep indigo — matches the pre-detection splash look.
    DEFAULT_ICON_COLOR = [60, 90, 200].freeze

    # System fonts to fall back on when the project's font isn't present
    # — mainly used in the default/hub state where no project has been
    # extracted yet but we still want the footer text visible. Roboto is
    # the Android default and present on every device since ~4.0;
    # DroidSans was its predecessor and survives on very old devices.
    SYSTEM_FONT_FALLBACKS = [
      '/system/fonts/Roboto-Regular.ttf',
      '/system/fonts/DroidSans.ttf',
    ].freeze

    module_function

    def load_project_info
      raw  = File.read(INFOS_CONFIG_PATH)
      info = JSON.parse(raw, symbolize_names: true)
      title   = info[:gameTitle].to_s.empty? ? 'PSDK Game' : info[:gameTitle].to_s
      version = info[:gameVersion] ? "v#{Util.format_version(info[:gameVersion])}" : ''
      { title: title, version: version }
    rescue StandardError => e
      loader_say "infos_config: #{e.class}: #{e.message}"
      { title: 'PSDK Game', version: '' }
    end

    # Median RGB triple written by extract_loader_assets.rb's
    # detect_icon_color. Falls back to DEFAULT_ICON_COLOR if the file is
    # missing (extraction skipped on an all-transparent icon) or
    # malformed.
    def load_icon_color
      raw = File.read(ICON_COLOR_PATH)
      c   = JSON.parse(raw, symbolize_names: true)
      [
        c[:r].to_i.clamp(0, 255),
        c[:g].to_i.clamp(0, 255),
        c[:b].to_i.clamp(0, 255),
      ]
    rescue StandardError => e
      loader_say "icon_color: #{e.class}: #{e.message}"
      DEFAULT_ICON_COLOR.dup
    end

    # The "preparing" message, or nil when the splash is in its normal
    # hub/project state.
    def load_preparing_message
      return nil unless File.exist?(PREPARING_PATH)
      text = File.read(PREPARING_PATH).strip
      text.empty? ? nil : text
    rescue StandardError => e
      loader_say "preparing message: #{e.class}: #{e.message}"
      nil
    end

    def load_icon_bitmap
      data = File.read(ICON_PATH)
      tmp  = LiteRGSS::Image.new(data, true)
      out  = LiteRGSS::Bitmap.new(tmp.width, tmp.height)
      tmp.copy_to_bitmap(out)
      tmp.dispose
      out
    rescue StandardError => e
      loader_say "icon load failed: #{e.class}: #{e.message}"
      nil
    end

    # LiteRGSS::Text raises if its font id isn't registered. PSDK
    # normally walks Configs.texts.fonts at Graphics.on_start — we don't
    # have that yet, so register the well-known PSDK default ourselves,
    # falling back to a system font so the footer renders in the
    # default state.
    # Loads the splash's font as font 0: the game's, else the system's.
    # True when one loads.
    def ensure_font_loaded(font_path: DEFAULT_FONT_PATH)
      ([font_path] + SYSTEM_FONT_FALLBACKS).each do |path|
        next unless File.exist?(path)
        begin
          LiteRGSS::Fonts.load_font(0, path)
          LiteRGSS::Fonts.set_default_size(0, 13)
          @font_path = path
          return true
        rescue StandardError => e
          loader_say "font load failed (#{path}): #{e.class}: #{e.message}"
        end
      end
      @font_path = nil
      false
    end

    # The font id drawing the splash's font at `size`. LiteRGSS3 (raylib)
    # rasterizes a font once, at its default size, and scales its glyphs
    # to the size a text asks for: a pixel font drawn at another size gets
    # columns doubled or dropped (nearest sampling). Each size gets a font
    # id of its own, rasterized at that size; kept for the VM's life, which
    # the splash's runs share. 0 (the default size) without a font.
    def font_id(size)
      return 0 unless @font_path

      @font_ids ||= {}
      @font_ids[[@font_path, size]] ||= begin
        id = FIRST_SIZED_FONT_ID + @font_ids.size
        LiteRGSS::Fonts.set_default_size(id, size)
        LiteRGSS::Fonts.load_font(id, @font_path)
        id
      end
    rescue StandardError => e
      loader_say "font at #{size}: #{e.class}: #{e.message}"
      0
    end
  end
end

end # unless defined?(Loader::Assets::DEFAULT_FONT_PATH)
