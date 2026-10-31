unless defined?(Loader::HostMessages::PREFIX)

module Loader
  # Applies the messages the host posts to the splash (LoaderMessages.kt),
  # read with VMHost.each_message once per frame:
  #
  #   loader.status <text>  — the status line (a compile sub-step, a CTA)
  #   loader.lift <text>    — start the post-compile lift (room for the
  #                           result card), then show <text>
  #   loader.unlift         — settle the centerpiece back down once the
  #                           user dismisses the result card
  #   loader.tap            — play the release-on-icon visual (auto-tap from
  #                           the classic-bootstrap path, where there's no
  #                           finger)
  #   loader.reset <text>   — the wizard's post-dismiss in-place state wipe
  #                           (see the handler below), then show <text>
  #   loader.clear          — a new splash state (RootActivity's
  #                           setLoaderState): the messages before it are
  #                           not applied, and nothing is applied again
  #
  # A splash restarted in the same state (after a resize, back from the
  # game) shows what the previous one showed: the last message applied is
  # kept for the VM's life, which every splash shares, and applied again by
  # the next splash's first read. Whichever splash takes a message, the
  # dying one or the new one, the next splash gets it. Only that message,
  # not the scene: an unlift restores as nothing until the next status.
  #
  # Best-effort: a message that raises is lost, and so are the ones taken
  # with it.
  class HostMessages
    PREFIX = 'loader.'.freeze
    CLEAR  = 'loader.clear'.freeze

    class << self
      # [topic, payload] of the last message applied, nil after a clear
      attr_accessor :last
    end

    def initialize(state:, text_layer:, dust:, icon:, clock:)
      @state = state
      @text  = text_layer
      @dust  = dust
      @icon  = icon
      @clock = clock
      @first_read = true
      @last_status = nil
    end

    def poll
      messages = []
      VMHost.each_message(PREFIX) { |topic, payload| messages << [topic, payload] }
      if (cleared = messages.rindex { |topic, _| topic == CLEAR })
        messages = messages.drop(cleared + 1)
        self.class.last = nil
      end
      messages.unshift(self.class.last) if @first_read && self.class.last
      @first_read = false
      messages.each do |topic, payload|
        apply(topic, payload)
        self.class.last = [topic, payload]
      end
    rescue StandardError => e
      loader_say "host messages: #{e.class}: #{e.message}"
    end

    private

    def apply(topic, payload)
      case topic
      when 'loader.status'
        show_status(payload)
      when 'loader.lift'
        # Idempotent: once lifted, a repeat only updates the status
        @state.lifted_start ||= @clock.frame
        show_status(payload)
      when 'loader.unlift'
        # Mirror of the lift. No-op on a never-lifted scene:
        # lift_offset_for short-circuits on lifted_start anyway.
        @state.unlifted_start ||= @clock.frame
      when 'loader.tap'
        # rolling_start ||= so a repeat doesn't reset the spin ramp; the
        # activity only taps once per classic bootstrap anyway.
        @state.rolling_start ||= @clock.frame
        @state.shake_start = @clock.frame
        @dust.bang(@icon.center_x, @icon.center_y, born_at: @clock.frame)
      when 'loader.reset'
        # Wipe what the compile cycle accumulated and snap the icon back,
        # in place of a workload restart (which would pay show!'s asset
        # reload and the intro hold before the new splash is visible).
        # external_status_received stays: the wizard always sends a real
        # CTA with the reset, so loader.rb's default CTA stays suppressed.
        @state.rolling_start  = nil
        @state.lifted_start   = nil
        @state.unlifted_start = nil
        @state.shake_start    = nil
        @state.pressed_finger = nil
        @icon.reset_visual!
        show_status(payload)
      end
    end

    def show_status(text)
      text = text.strip
      return if text.empty? || text == @last_status

      @last_status = text
      @text.update_status(text)
      @state.external_status_received = true
    end
  end
end

end # unless defined?(Loader::HostMessages::PREFIX)
