# PSDK's error window, replaced on Android.
#
# After a script error, PSDK (Yuki::EXC, "100 Error Logger.rb") writes
# Error.log, then Yuki::EXC.show_error_window saves the game into a picture
# and opens Yuki::ErrorWindow: a second DisplayWindow over the game, waiting
# for its on_closed. Android fires it only when the game screen goes away, so
# the player was stuck on that window. Without a game (title screens), the
# save failed instead, its error replacing the script's in Error.log.
#
# Instead, the error goes to the host as a SCRIPT_ERROR_EVENT ("Type:
# message") and PSDK goes on as after the window closed: an error in the game
# loop ends the game (the app then reports Error.log), an error in an event
# script lets it continue (the game screen says where the log is).
#
# Installed by start.rb before Game.rb, for any PSDK version: prepended
# modules keep precedence over the methods PSDK defines afterwards.
# Yuki::EXC is hooked when PSDK opens it (it can't be declared ahead: PSDK
# takes its presence for loaded scripts), Yuki::ErrorWindow is declared
# ahead, for a PSDK opening it from elsewhere.
#
# Guarded like touch_layouts.rb: a prelude of start.rb.
unless defined?(ScriptErrors::SCRIPT_ERROR_EVENT)

module ScriptErrors
  module_function

  # GameActivity's SCRIPT_ERROR_EVENT
  SCRIPT_ERROR_EVENT = 'game.script_error'.freeze
  SUMMARY_MAX = 300

  # Yuki::EXC.show_error_window, without the save picture and the window
  module NoErrorWindow
    def show_error_window(log)
      ScriptErrors.report(log)
    end
  end

  # Yuki::ErrorWindow#run, without the window
  module NoWindow
    def run(error_text, *)
      ScriptErrors.report(error_text)
    end
  end

  def install
    Object.const_set(:Yuki, Module.new) unless Object.const_defined?(:Yuki, false)
    # PSDK's `class ErrorWindow` reopens it
    Yuki.const_set(:ErrorWindow, Class.new) unless Yuki.const_defined?(:ErrorWindow, false)
    Yuki::ErrorWindow.prepend(NoWindow)
    return hook_exc if Yuki.const_defined?(:EXC, false)

    trace = TracePoint.new(:class) do |tp|
      # Module#name: a class may redefine its own
      next unless Module.instance_method(:name).bind_call(tp.self) == 'Yuki::EXC'

      trace.disable
      hook_exc
    end
    trace.enable
  end

  def hook_exc
    Yuki::EXC.singleton_class.prepend(NoErrorWindow)
  end

  def report(error_text)
    warn "[script_errors] #{SCRIPT_ERROR_EVENT} not sent: no host" unless VMHost.emit(SCRIPT_ERROR_EVENT, summary(error_text))
  rescue StandardError => e
    warn "[script_errors] #{SCRIPT_ERROR_EVENT} not sent: #{e.class}: #{e.message}"
  end

  # "Type: message" of a PSDK error log ("Message :" and the message on the
  # next line, then "Type : X"), else its first meaningful line.
  # @param log [String]
  # @return [String]
  def summary(log)
    text = log.to_s
    message = text[/^Message :\r?\n([^\r\n]*)/, 1]&.strip
    type = text[/^Type : ([^\r\n]*)/, 1]&.strip
    summary = [type, message].compact.reject(&:empty?).join(': ')
    summary = text.lines.map(&:strip).find { |line| !line.empty? && !line.start_with?('=') }.to_s if summary.empty?
    summary[0, SUMMARY_MAX]
  end
end

end
