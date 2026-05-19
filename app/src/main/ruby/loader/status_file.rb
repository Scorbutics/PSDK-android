module Loader
  # Polls the one-line status file the compile wizard writes during
  # compilation. Drives the splash's status_text and reacts to the two
  # sentinel payloads:
  #
  #   <<LIFT>> — kick off the post-compile lift (move centerpiece up to
  #              clear room for the result card).
  #   <<TAP>>  — synthesize a release-on-icon visual (auto-tap from the
  #              classic-bootstrap path, where there's no finger).
  #
  # mtime gates the read so the steady-state cost is one File.mtime
  # stat per POLL_INTERVAL_F frames — cheap on Android's ext4.
  #
  # Failures (file missing, permission flake, malformed bytes) leave
  # the previous status in place — same "best-effort" contract as the
  # Kotlin writer (CompileProgressFragment#pushStatus).
  class StatusFile
    # Relative path is fine because PsdkInterpreter.enqueue does
    # Dir.chdir to the executionLocation before running this script.
    PATH            = '.loader_status'.freeze
    POLL_INTERVAL_F = 8    # ~7.5 Hz at 60Hz — much faster than humans read
    BYTE_LIMIT      = 256  # guard vs. accidental dump of a megabyte log

    # Kept identical on the Kotlin side — CompileProgressFragment
    # writes <<LIFT>> verbatim from onCompilationFinished, right before
    # advancing the wizard to the result step. <<TAP>> is the
    # classic-bootstrap auto-tap from the activity.
    LIFT_SENTINEL = '<<LIFT>>'.freeze
    TAP_SENTINEL  = '<<TAP>>'.freeze

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
      return unless @text.status_present?
      begin
        return unless File.exist?(PATH)
        mtime = File.mtime(PATH)
        return if @last_mtime && mtime == @last_mtime
        @last_mtime = mtime
        payload = File.read(PATH, BYTE_LIMIT).to_s.strip
        return if payload.empty?

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
