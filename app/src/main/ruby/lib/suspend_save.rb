# The game's progress, kept when Android kills it in the background.
#
# A phone's memory optimiser kills an app in the background without
# warning: the progress since the player's last save was lost. When the app
# goes to the background, this writes the game to a suspend file
# (RGSS_SUSPEND_FILE, chosen by GameActivity), apart from the player's saves,
# which it never touches. Back in the foreground, the process survived: the
# file is deleted. Killed meanwhile, the file stays, and GameActivity offers
# to resume from it at the next launch (RGSS_RESUME_SUSPEND=1).
#
# What it knows of PSDK, kept to its save API and the RMXP globals:
# - GamePlay::Save.save(nil, true): the bytes of a save, written by PSDK
#   itself (no file); GamePlay::Save.load(file): a game state back.
# - a safe moment: Scene_Map, no event running, no message shown. A battle, a
#   menu, a cutscene isn't one (PSDK doesn't save a battle): the game written
#   is then the last safe moment's, kept in memory, taken every
#   SNAPSHOT_INTERVAL seconds and after each save of the player (~10 ms,
#   ~60 KB for Forêt Éternelle). A player leaving mid-battle resumes just
#   before it.
# - resuming: GamePlay::Load#load_game, PSDK's own way to enter a loaded game,
#   on a Load holding the suspend file's state, at the first scene of the
#   game (its title), which then stops as after PSDK's Load (@running: its
#   loop doesn't look at $scene).
#
# The background moment comes from litergss-everywhere's HostLifecycle: the
# window's LostFocus, delivered before the game pauses in the background.
#
# For the game's maker:
# - a part of the game where the player can't save (the event command
#   "Change Save Access": $game_system.save_disabled) is no safe moment
#   either: no snapshot there, the background writes the last one from
#   before it (a resume puts the player back where they could last save).
# - a script of the game turns it off, for the whole game or a part of it
#   (on desktop PSDK this module doesn't exist, hence the guard):
#     SuspendSave.enabled = false if defined?(SuspendSave)
#   Off, nothing is written in the background; true turns it back on.
#
# Guarded like the other preludes of start.rb.
unless defined?(SuspendSave::SNAPSHOT_INTERVAL)

module SuspendSave
  module_function

  # Seconds between two snapshots of a safe moment
  SNAPSHOT_INTERVAL = 30

  # Hooked on LiteRGSS::DisplayWindow: a snapshot or the resume, between frames
  module WindowTick
    def update(*args)
      result = super
      SuspendSave.tick
      result
    end
  end

  # Off (a script of the game): no snapshot, nothing written in the background
  def enabled=(value)
    @enabled = value ? true : false
  end

  def enabled?
    @enabled != false
  end

  # @param path [String, nil] the suspend file (RGSS_SUSPEND_FILE): none, no suspend save
  # @param resume [Boolean] resume from it at the first scene of the game
  # @param lifecycle [Module] LiteRGSS::HostLifecycle
  # @param clock [#call] seconds, monotonic
  def install(path:, resume:, lifecycle:, clock: -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) })
    return false if path.nil? || path.empty?

    @path = path
    @resume = resume && File.exist?(path)
    @clock = clock
    @snapshot = nil
    @snapshot_at = nil
    @save_count = nil
    # Not resumed: an older game's file, not this one's
    discard unless @resume
    lifecycle.install
    lifecycle.on_background { suspend }
    lifecycle.on_foreground { discard }
    LiteRGSS::DisplayWindow.prepend(WindowTick) unless LiteRGSS::DisplayWindow.ancestors.include?(WindowTick)
    true
  end

  # Between two frames: resume once the game is up, keep a snapshot of the
  # last safe moment
  def tick
    return try_resume if @resume
    return unless enabled? && safe?

    count = $game_system.save_count
    now = @clock.call
    return if @snapshot_at && count == @save_count && now - @snapshot_at < SNAPSHOT_INTERVAL

    @snapshot = snapshot
    @snapshot_at = now
    @save_count = $game_system.save_count
  rescue StandardError => e
    log "snapshot failed: #{e.class}: #{e.message}"
    @snapshot_at = @clock.call
  end

  # The app goes to the background: the game now if it's at a safe moment,
  # else its last safe moment
  def suspend
    return log('off (the game turned it off)') unless enabled?

    data = safe? ? snapshot : @snapshot
    return log('nothing to suspend yet (no safe moment so far)') unless data

    temp = "#{@path}.tmp"
    File.binwrite(temp, data)
    File.rename(temp, @path) # a kill while writing leaves the former file whole
    log "suspended (#{data.bytesize} bytes)"
  end

  # Back in the foreground: the process lives on, nothing to resume
  def discard
    File.delete(@path) if @path && File.exist?(@path)
  end

  # Scene_Map, no event running, no message shown, no transfer under way,
  # saving allowed (Change Save Access)
  def safe?
    return false unless defined?(::Scene_Map) && $scene.is_a?(::Scene_Map)
    return false unless $game_system && $game_temp && $game_player

    !$game_system.save_disabled && !$game_system.map_interpreter.running? &&
      !$game_temp.message_window_showing && !$game_temp.player_transferring
  end

  # A save's bytes, by PSDK. It counts as a save of the player
  # (save_count): put back, the live game doesn't see it.
  def snapshot
    count = $game_system.save_count
    GamePlay::Save.save(nil, true)
  ensure
    $game_system.save_count = count if count
  end

  # At the game's first scene (its title): enter the suspended game as
  # PSDK's Load does
  def try_resume
    return unless defined?(::GamePlay::Load) && defined?(::GamePlay::Base) && $scene.is_a?(::GamePlay::Base)

    @resume = false
    state = GamePlay::Save.load(@path, no_load_parameter: true)
    return log('suspend file unreadable: the game starts as usual') unless state

    shown = $scene
    loader = GamePlay::Load.allocate
    loader.instance_variable_set(:@all_saves, [state])
    loader.instance_variable_set(:@index, 0)
    loader.send(:load_game)
    # The title's loop runs while @running: it ends as after PSDK's Load
    shown.instance_variable_set(:@running, false) unless $scene.equal?(shown)
    discard
    log 'resumed from the suspend file'
  rescue StandardError => e
    log "resume failed: #{e.class}: #{e.message}"
  end

  def log(message)
    text = "[SuspendSave] #{message}"
    defined?(VMLogger) ? VMLogger.info(text) : warn(text)
    nil
  end
end

end
