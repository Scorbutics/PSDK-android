# Process-global helpers used by every other loader module.
#
# Concatenated as the first prelude by RubyScript — see RootActivity.kt's
# LOADER_PRELUDES — so loader_say, Loader::Util, and LiteRGSS are
# available to everything that follows.
#
# The whole body is guarded by `unless defined?(LOADER_BASE_DIR)` so a
# second loader workload start (after a swap-and-restart) doesn't re-
# eval the constant assignments — that path would otherwise spam
# "already initialized constant" warnings into logcat for every prelude
# the persistent interpreter re-runs. The classes/methods/requires
# inside are all idempotent on their own, but constants warn on reassign.

unless defined?(LOADER_BASE_DIR)

# The loader's cwd at startup — captured BEFORE any sibling interpreter
# (the compile engine, archive validator, etc.) can run their own
# `Dir.chdir`. `Dir.chdir` without a block is `chdir(2)`, which is
# *process*-wide, so a concurrent compile interpreter chdir'd to a
# project dir would silently break our relative `.loader_status` poll
# and asset reads. Absolute paths via this constant sidestep the race.
LOADER_BASE_DIR = Dir.pwd.freeze

# Sticky close request (RubyWorkloadController writes it next to calling
# NativeSurface.requestClose, and removes it before launching a loader).
# requestClose is a no-op while no window exists yet, e.g. during the
# extract prelude or show!'s asset loading: the splash would then never
# see it, never exit, and the host's interpreter teardown would wait on
# it forever.
LOADER_STOP_PATH = "#{LOADER_BASE_DIR}/.loader_stop".freeze

def loader_stop_requested?
  File.exist?(LOADER_STOP_PATH)
rescue StandardError
  false
end

def loader_say(msg)
  puts "[loader] #{msg}"
rescue StandardError
  STDERR.puts "[loader] #{msg}" rescue nil
end

require 'LiteRGSS'

module Loader
  module Util
    module_function

    def ease_out_cubic(t)
      return 0.0 if t <= 0.0
      return 1.0 if t >= 1.0
      1.0 - ((1.0 - t) ** 3)
    end

    # Pack a 32-bit big-endian int as a dotted-decimal version string and
    # trim leading "0." segments — turns 0x00010203 into "1.2.3".
    def format_version(n)
      [n].pack('I>').unpack('C*').join('.').gsub(/^(0\.)+/, '')
    end
  end
end

end # unless defined?(LOADER_BASE_DIR)
