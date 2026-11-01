# The on-screen gamepad's layout files (the runtime's
# LiteRGSS::TouchOverlay.load_layout):
#   - the game's, Data/touch_layout.json, its images relative to Data/;
#   - the player's, touch_layout.json in the project's execution location
#     (beside Release/), written when the player saves in the overlay's
#     editor; its images relative to it.
#
# Guarded like epsa_format.rb: an ARCHIVE_PRELUDE, evaluated again by every
# script of the reused compile VM.
unless defined?(TouchLayouts::GAME_FILE)

require 'json'
require 'fileutils'

module TouchLayouts
  module_function

  GAME_FILE = 'Data/touch_layout.json'.freeze
  PLAYER_FILE = 'touch_layout.json'.freeze

  # A release's files, read like PhysFS reads the archive's
  module ReleaseFiles
    module_function

    def exist?(path) = File.exist?(path)
    def read(path) = File.binread(path)
  end

  # Copy the game's file and its images from the archive into a release:
  # a project's compilation leaves them out. A broken file never fails the
  # compile: the runtime ignores it, the game has the default layout.
  # @param fs [#exist?, #read] the mounted archive (PhysFS)
  # @return [Integer] files copied
  def install_game_file(fs, release_dir)
    return 0 unless fs.exist?(GAME_FILE)

    paths = [GAME_FILE] + images(fs.read(GAME_FILE)).map { |image| File.join(File.dirname(GAME_FILE), image) }
    paths.count do |path|
      next false unless fs.exist?(path)

      target = File.join(release_dir, path)
      FileUtils.mkdir_p(File.dirname(target))
      File.binwrite(target, fs.read(path))
    end
  rescue StandardError => e
    warn "[touch_layouts] #{GAME_FILE} not installed: #{e.class}: #{e.message}"
    0
  end

  # Load the game's and the player's files into the overlay (each on its
  # own: a broken one leaves the other), the editor's saves go to the
  # player's. A player's file the overlay refuses is kept aside, not
  # overwritten by the next save.
  # @param fs [#exist?, #read] where the game's files are: PhysFS, or
  #   ReleaseFiles in Release/
  def load(overlay, fs, execution_location)
    begin
      if fs.exist?(GAME_FILE)
        dir = File.dirname(GAME_FILE)
        overlay.load_layout(fs.read(GAME_FILE), read_image: ->(path) { fs.read(image_path(dir, path)) })
      end
    rescue StandardError => e
      warn "[touch_layouts] #{GAME_FILE} not loaded: #{e.class}: #{e.message}"
    end
    player = File.join(execution_location, PLAYER_FILE)
    begin
      if File.exist?(player)
        loaded = overlay.load_layout(File.read(player), read_image: ->(path) { File.binread(image_path(execution_location, path)) },
                                                        player: true)
        File.rename(player, "#{player}.broken") unless loaded
      end
    rescue StandardError => e
      warn "[touch_layouts] #{player} not loaded: #{e.class}: #{e.message}"
    end
    overlay.on_layout_saved = ->(json) { write(player, json) }
  end

  # The tools menu's Settings: the app opens its Controls screen over the
  # game (GameActivity, on SETTINGS_EVENT), which changes the player's file,
  # then posts RELOAD_MESSAGE. The game's thread reads it after each window
  # update and loads both files again.
  # @param window_class [Class] LiteRGSS::DisplayWindow
  def serve_settings(overlay, fs, execution_location, window_class)
    # A runtime before 1.5.0 has no Settings in the menu
    return unless overlay.respond_to?(:on_settings=)

    overlay.on_settings = lambda do
      warn "[touch_layouts] #{SETTINGS_EVENT} not sent: no host" unless VMHost.emit(SETTINGS_EVENT)
    end
    Reload.action = lambda do
      overlay.clear_layout
      load(overlay, fs, execution_location)
    end
    window_class.prepend(Reload) unless window_class.ancestors.include?(Reload)
  end

  SETTINGS_EVENT = 'overlay.settings'.freeze
  RELOAD_MESSAGE = 'overlay.reload'.freeze

  # Hooked on the window's update (serve_settings)
  module Reload
    class << self
      attr_accessor :action

      # A failing reload must not reach the game, nor stay pending forever
      def check
        VMHost.each_message(RELOAD_MESSAGE) do |topic, _|
          next unless topic == RELOAD_MESSAGE

          begin
            action&.call
          rescue StandardError => e
            warn "[touch_layouts] layout not reloaded: #{e.class}: #{e.message}"
          end
        end
      rescue StandardError => e
        warn "[touch_layouts] layout reload not read: #{e.class}: #{e.message}"
      end
    end

    def update(*args)
      result = super
      Reload.check
      result
    end
  end

  # Whole or not at all: the game may be killed while it writes
  def write(path, json)
    tmp = "#{path}.tmp"
    File.write(tmp, json)
    File.rename(tmp, path)
  ensure
    File.delete(tmp) if tmp && File.exist?(tmp)
  end

  # The images a layout file names, inside its directory only
  def images(json)
    data = JSON.parse(json)
    buttons = data['buttons'] if data.is_a?(Hash)
    return [] unless buttons.is_a?(Hash)

    buttons.each_value.filter_map { |entry| entry['image'] if entry.is_a?(Hash) && safe?(entry['image']) }.uniq
  rescue JSON::ParserError
    []
  end

  def image_path(dir, path)
    raise ArgumentError, "#{path} is outside #{dir}" unless safe?(path)

    File.join(dir, path)
  end

  # Inside its directory, a path any file system reads alike (a backslash
  # separates on Windows): the exporter packs a --compiled release's images
  # by the same rule (exporter/export_android.rb touch_layout_files)
  def safe?(path)
    path.is_a?(String) && path.valid_encoding? && !path.empty? && !path.start_with?('/') && !path.end_with?('/') &&
      !path.include?('\\') && !path.include?("\0") && !path.split('/').include?('..')
  end
end

end # unless defined?(TouchLayouts::GAME_FILE)
