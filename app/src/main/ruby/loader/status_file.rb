module Loader
  # Polls the one-line status file the compile wizard writes during
  # compilation. Drives the splash's status_text and reacts to the two
  # sentinel payloads:
  #
  #   <<LIFT>>   — kick off the post-compile lift (move centerpiece up
  #                to clear room for the result card).
  #   <<UNLIFT>> — settle the centerpiece back down once the user
  #                dismisses the result card.
  #   <<TAP>>    — synthesize a release-on-icon visual (auto-tap from
  #                the classic-bootstrap path, where there's no finger).
  #
  # mtime gates the read so the steady-state cost is one File.mtime
  # stat per POLL_INTERVAL_F frames — cheap on Android's ext4.
  #
  # Failures (file missing, permission flake, malformed bytes) leave
  # the previous status in place — same "best-effort" contract as the
  # Kotlin writer (CompileProgressFragment#pushStatus).
  class StatusFile
    # Absolute path anchored at the loader's startup cwd. A relative
    # path would race against any sibling Ruby interpreter's chdir
    # (Dir.chdir is process-wide), and we know from the compile-engine
    # path that one fires right after the user taps the logo — the
    # splash poll then resolves `.loader_status` against the project
    # dir instead of the app data dir and silently misses every update.
    PATH            = "#{LOADER_BASE_DIR}/.loader_status".freeze
    POLL_INTERVAL_F = 8    # ~7.5 Hz at 60Hz — much faster than humans read
    BYTE_LIMIT      = 256  # guard vs. accidental dump of a megabyte log

    # Kept identical on the Kotlin side — CompileProgressFragment writes
    # <<LIFT>> verbatim from onCompilationFinished and CompileDoneFragment
    # writes <<UNLIFT>> from the success-card dismiss handler. <<TAP>> is
    # the classic-bootstrap auto-tap from the activity. <<RESET>> is the
    # wizard's post-dismiss in-place state wipe — see the handler below
    # for why we don't restart the workload to achieve the same.
    LIFT_SENTINEL   = '<<LIFT>>'.freeze
    UNLIFT_SENTINEL = '<<UNLIFT>>'.freeze
    TAP_SENTINEL    = '<<TAP>>'.freeze
    RESET_SENTINEL  = '<<RESET>>'.freeze

    def initialize(state:, text_layer:, dust:, icon:, clock:)
      @state = state
      @text  = text_layer
      @dust  = dust
      @icon  = icon
      @clock = clock
      # -infinity-initial frame forces a poll on the very first tick so
      # any status the activity wrote BEFORE the loader finished
      # initializing is picked up immediately.
      @last_poll_frame = -POLL_INTERVAL_F
      @last_mtime      = nil
      @last_payload    = nil
    end

    def poll
      return if (@clock.frame - @last_poll_frame) < POLL_INTERVAL_F
      @last_poll_frame = @clock.frame
      unless @text.status_present?
        return
      end
      begin
        unless File.exist?(PATH)
          return
        end
        mtime = File.mtime(PATH)
        if @last_mtime && mtime == @last_mtime
          return
        end
        @last_mtime = mtime
        payload = File.read(PATH, BYTE_LIMIT).to_s.strip
        return if payload.empty?

        if payload.start_with?(RESET_SENTINEL)
          # Reset sentinel: wipe the SceneState fields the compile cycle
          # accumulated (lifted/unlifted/rolling/shake/pressed) and snap
          # the icon's angle back to 0. Used by the wizard's success and
          # failure dismiss handlers in lieu of a workload restart —
          # restarting would have to pay the loader.rb ensure block's
          # PSDKVMSnapshot.log_diff!/restore! cost, which on a post-
          # compile shared VM enumerates every PSDK script as a "DROP"
          # and blocks the render thread for seconds. In-place reset
          # keeps the same Loader::Splash alive, so the next status
          # poll picks up the trailing CTA payload immediately. We
          # intentionally do NOT touch external_status_received: the
          # wizard always writes a real CTA in the same payload, so the
          # loader.rb default-CTA branch should stay suppressed.
          @state.rolling_start    = nil
          @state.lifted_start     = nil
          @state.unlifted_start   = nil
          @state.shake_start      = nil
          @state.pressed_finger   = nil
          @icon.reset_visual!
          payload = payload[RESET_SENTINEL.length..].to_s.strip
          return if payload.empty?
        end

        if payload.start_with?(LIFT_SENTINEL)
          # Lift sentinel: trigger the move-up animation (idempotent —
          # subsequent writes are ignored once lifted_start is set),
          # then use whatever comes after the token as the new status
          # text. Blank tail leaves status unchanged so the last
          # sub-step ("Finalizing…") stays put during the lift.
          @state.lifted_start ||= @clock.frame
          payload = payload[LIFT_SENTINEL.length..].to_s.strip
          return if payload.empty?
        end

        if payload.start_with?(UNLIFT_SENTINEL)
          # Mirror of LIFT: settles the centerpiece back to its resting
          # position. ||= keeps this idempotent against repeat writes.
          # No-op against a never-lifted scene because lift_offset_for
          # short-circuits on lifted_start anyway.
          @state.unlifted_start ||= @clock.frame
          payload = payload[UNLIFT_SENTINEL.length..].to_s.strip
          return if payload.empty?
        end

        if payload.start_with?(TAP_SENTINEL)
          # Auto-tap sentinel: synthesize the release-on-icon visual
          # that a real touch_ended would produce. rolling_start uses
          # ||= so repeat sentinels don't reset the spin ramp;
          # shake_start is plain assignment so a second tap WOULD
          # re-shake — but the activity only fires this once per
          # classic bootstrap so it doesn't matter in practice.
          @state.rolling_start ||= @clock.frame
          @state.shake_start = @clock.frame
          @dust.bang(@icon.center_x, @icon.center_y, born_at: @clock.frame)
          payload = payload[TAP_SENTINEL.length..].to_s.strip
          return if payload.empty?
        end

        return if payload == @last_payload
        @last_payload = payload
        @text.update_status(payload)
        @state.external_status_received = true
      rescue StandardError => e
        loader_say "status poll: #{e.class}: #{e.message}"
      end
    end
  end
end
