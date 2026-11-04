# The game's Ruby warnings (Kernel#warn, the interpreter's own), by base APK.
#
# PSDK's scripts reassign constants on every start: Ruby warns "already
# initialized constant" and "previous definition of ... was here" for each.
# - A release base APK silences every warning while the game runs
#   ($VERBOSE nil), as PSDK's release mode does on desktop (stderr to
#   /dev/null): a player's logcat and game logs keep the game's output, its
#   errors and crashes (Error.log), not its warnings.
# - A debug base APK keeps them (the overlay's, the game's...), in logcat and
#   the game's logs, all but those two.
# Whether the base APK is a debug one comes from the app (DEBUG_ENV, set
# from BuildConfig.DEBUG), not from the game mode: an exported game always
# runs compiled, from a debug base APK too.
#
# Guarded like touch_layouts.rb: a prelude of start.rb.
unless defined?(GameWarnings::DEBUG_ENV)

module GameWarnings
  module_function

  DEBUG_ENV = 'RGSS_APP_DEBUG'.freeze
  # Ruby's warnings about a constant reassigned
  CONSTANT_REASSIGNED = /warning: (already initialized constant|previous definition of) /.freeze

  # A debug base APK (DEBUG_ENV is "1")
  def debug?(env = ENV)
    env[DEBUG_ENV] == '1'
  end

  # Set the warnings the game gets
  # @return [Object] $VERBOSE before, for restore
  def install(env = ENV)
    previous = $VERBOSE
    if debug?(env)
      # A second prepend of it changes nothing
      Warning.singleton_class.prepend(DropConstantReassigned)
    else
      $VERBOSE = nil
    end
    previous
  end

  # $VERBOSE as install found it
  def restore(previous)
    $VERBOSE = previous
  end

  # On Warning.warn, which all of Ruby's warnings go through
  module DropConstantReassigned
    def warn(message, *args, **kwargs)
      return nil if message.is_a?(String) && message.match?(CONSTANT_REASSIGNED)

      super
    end
  end
end

end
