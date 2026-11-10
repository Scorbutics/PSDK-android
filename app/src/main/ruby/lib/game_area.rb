# PSDK's game, centred on the phone's window by LiteRGSS instead of by
# PSDK's viewport offsets.
#
# On a fullscreen platform, PSDK (000 PSDK_CONFIG.rb) opens a window of the
# screen's aspect ratio (534x240 for a 320x240 game on a 20:9 phone) and
# centres the game in it with PSDK_CONFIG.viewport_offset_x / _y, which only
# Viewport.create adds. The rest of the game reads the window: Graphics.width
# is the window's width, so what is placed with it (Battle 3D's background,
# the PSP move animations, the timer...) or drawn on the window itself (the
# frozen screen of a transition) lands beside the game.
#
# Once PSDK created its window, its offsets become the window's game area
# (LiteRGSS's DisplayWindow#game_area=) and are set back to 0 in PSDK:
# LiteRGSS centres the game, and the game sees a window of its own size
# (width, height, mouse and touch positions), whatever its PSDK version. The
# touch overlay keeps the whole window.
#
# Nothing changes for a game as large as its window (no offset), a game
# placing its viewports itself (Viewport's own global offsets), or a runtime
# without game area (no DisplayWindow#game_area=).
#
# Guarded like touch_layouts.rb: a prelude of start.rb.
unless defined?(PsdkGameArea::Creation)

module PsdkGameArea
  # Prepended to LiteRGSS::DisplayWindow's singleton: the game area of the
  # window PSDK just created
  module Creation
    def new(*args, &block)
      window = super
      PsdkGameArea.apply(window)
      window
    end
  end

  module_function

  # Hook the windows' creation (idempotent)
  # @return [Boolean] if the game area will be applied
  def install(window_class = LiteRGSS::DisplayWindow)
    return false unless window_class.method_defined?(:game_area=)

    creation = window_class.singleton_class
    creation.prepend(Creation) unless creation.ancestors.include?(Creation)
    true
  end

  # Move PSDK's viewport offsets to the window, when it has some. Never stops
  # the game: an error leaves PSDK's offsets.
  # @param window [LiteRGSS::DisplayWindow]
  # @return [Array(Integer, Integer, Integer, Integer), nil] the game area given
  def apply(window, config = psdk_config)
    area = area_of(window, config)
    return nil unless area

    window.game_area = area
    config.instance_variable_set(:@viewport_offset_x, 0)
    config.instance_variable_set(:@viewport_offset_y, 0)
    log(:info, "game area #{area.inspect} on a #{window.screen_width}x#{window.screen_height} window")
    area
  rescue StandardError => e
    log(:error, "game area not applied, PSDK keeps its offsets: #{e.class}: #{e.message}")
    nil
  end

  # @return [Array(Integer, Integer, Integer, Integer), nil] [x, y, width, height]
  def area_of(window, config)
    return nil unless config.respond_to?(:viewport_offset_x) && config.respond_to?(:viewport_offset_y)
    # Applied once a window: the error window PSDK opens later finds no offset
    return nil unless window.respond_to?(:game_area=) && window.game_area.nil?

    x = config.viewport_offset_x.to_i
    y = config.viewport_offset_y.to_i
    return nil if (x.zero? && y.zero?) || x.negative? || y.negative? || own_offsets?

    width, height = game_size(window, x, y)
    return nil unless width.positive? && height.positive?
    return nil if x + width > window.width || y + height > window.height

    [x, y, width, height]
  end

  # The game's resolution (Studio's projects), else the window without the
  # offsets on both sides
  def game_size(window, x, y)
    resolution = ::Configs.display.game_resolution if defined?(::Configs)
    return [resolution.x.to_i, resolution.y.to_i] if resolution.respond_to?(:x) && resolution.respond_to?(:y)

    [window.width - 2 * x, window.height - 2 * y]
  rescue StandardError
    [window.width - 2 * x, window.height - 2 * y]
  end

  # A game setting Viewport's own global offsets places its viewports itself
  def own_offsets?
    return false unless defined?(::Viewport)

    !(::Viewport.instance_variable_get(:@global_offset_x).nil? && ::Viewport.instance_variable_get(:@global_offset_y).nil?)
  end

  def psdk_config
    defined?(::PSDK_CONFIG) ? ::PSDK_CONFIG : nil
  end

  def log(level, message)
    return unless defined?(VMLogger)

    VMLogger.public_send(level, "[PsdkGameArea] #{message}")
  end
end

end
