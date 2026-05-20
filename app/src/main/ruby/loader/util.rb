# Process-global helpers used by every other loader module.
#
# Concatenated as the first prelude by RubyScript — see RootActivity.kt's
# LOADER_PRELUDES — so loader_say, Loader::Util, and LiteRGSS are
# available to everything that follows.

# The loader's cwd at startup — captured BEFORE any sibling interpreter
# (the compile engine, archive validator, etc.) can run their own
# `Dir.chdir`. `Dir.chdir` without a block is `chdir(2)`, which is
# *process*-wide, so a concurrent compile interpreter chdir'd to a
# project dir would silently break our relative `.loader_status` poll
# and asset reads. Absolute paths via this constant sidestep the race.
LOADER_BASE_DIR = Dir.pwd.freeze

def loader_say(msg)
  puts "[loader] #{msg}"
rescue StandardError
  STDERR.puts "[loader] #{msg}" rescue nil
end

require 'LiteRGSS'
LiteRGSS::Shader.available = false

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
