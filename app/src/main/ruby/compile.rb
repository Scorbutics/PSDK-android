# compile.rb
#
# Stand-alone compile entrypoint. check_engine.rb has already mounted the
# archive in this same compile interpreter (process-wide PhysFS state),
# so we just configure PSDK's load path, ARGV, and the writable scaffold
# that project_compilation expects, then `require './Game.rb'` to let
# PSDK's PARGV[:util] dispatcher drive the build.
#
# The splash loop (loader.rb on a separate PsdkInterpreter) keeps
# ticking on its own thread while this script runs. loader.rb uses raw
# LiteRGSS primitives only — PSDK's class reopenings happen during the
# require below but don't disturb it.

require 'physfs'
require 'fileutils'

# Writes go to the real filesystem rooted at the activity's data dir.
# PSDK / project_compilation expect mkdir Release/, mtime updates, etc.
# all of which need write_dir set. Previously loader.rb seeded this on
# its own interpreter; with loader on a separate interpreter we set it
# explicitly here.
PhysFS.write_dir = Dir.pwd.end_with?('/') ? Dir.pwd : "#{Dir.pwd}/"

# Always start from a clean Release/. PhysFS reads still hit the mounted
# archive, so wiping Release/ here is safe.
FileUtils.rm_rf('./Release')
raise "Unable to remove existing compiled data" if File.exist?('Release')

# ./Data on the native FS is compile scratch only: project data is read
# from the mounted archive. Wipe it like Release/, because whatever a
# failed or interrupted compile left there shadows the archive on the
# next one. One such run wrote default configs serialized as
# "#<Configs::Project::Infos...>" to Data/configs/; every later compile
# packed them into Release/ (nil game title: the game crashed on start)
# and the splash showed "PSDK Game".
FileUtils.rm_rf('./Data')

# PSDK's data-build expects Data/Events/Battle/ to be present on the
# native FS even though every file inside is empty/optional. Without
# this directory the data-build step raises.
FileUtils.mkdir_p './Data/Events/Battle'

# Marker files PSDK reads at startup. `project.studio` short-circuits
# Studio::Text.reload_rh_texts's source-mtime check (archive ships
# compiled .dat but not the source Data/Text/<lang>.dat).
# `Saves/input.json` keeps GamePlay::KeyBinding.load_inputs off its
# save_inputs fallback, which would write into a missing dir. Both used
# to live in mining_game_demo.rb; with the demo gone, compile.rb owns
# the setup.
studio_marker = File.join(PhysFS.write_dir, 'project.studio')
File.write(studio_marker, '') unless File.exist?(studio_marker)
saves_dir = File.join(PhysFS.write_dir, 'Saves')
Dir.mkdir(saves_dir) rescue Errno::EEXIST
input_path = File.join(saves_dir, 'input.json')
File.write(input_path, '{}') unless File.exist?(input_path)

# Stale --util payload from a prior interpreter run could silently
# redirect the require below into the wrong code path.
File.delete(File.join(PhysFS.write_dir, '.gameopts')) rescue nil

# project_compilation reads ARGV to gate optional steps; --util=
# short-circuits ScriptLoad.rb's project-script load and Z_main, then
# pulls in the tool by name. project_compilation.rb's file-body tails
# into `rgss_main {}` + `ProjectCompilation.start`, so the heavy work
# runs inline on this thread.
ARGV << "skip_lib"    unless ARGV.include?('skip_lib')
ARGV << "skip_binary" unless ARGV.include?('skip_binary')
util_flag = "--util=project_compilation"
ARGV << util_flag unless ARGV.include?(util_flag)

# The Ruby VM is a process-level singleton reused across compilation runs,
# so `require './Game.rb'` is idempotent by default — a second compile in
# the same app session would silently no-op and leave ./Release missing,
# tripping copy_saves.rb's "Unable to find a valid release folder" guard.
#
# Purge $LOADED_FEATURES entries rooted under the project's cwd so every
# compile re-executes Game.rb + PSDK scripts from scratch, closer to a
# clean-VM sandbox. Native extensions (LiteRGSS, physfs) live outside this
# prefix and aren't touched — they're statically linked and re-init is
# neither possible nor needed.
#
# The embedded Ruby stdlib is installed under the app data dir as well
# (<data>/files/ruby-vm/ruby/<ruby ABI version>/...), which IS the cwd in classic mode.
# Keep those entries: re-executing openssl.rb & co. on top of their already
# initialized C extensions raises (PSDK's `require 'openssl'` failed with
# "superclass mismatch for class Cipher" once epsa_stream.rb had loaded it).
# rbconfig.rb sits in <stdlib>/<arch>/, so its grandparent is the stdlib root.
pwd_prefix = "#{Dir.pwd}/"
rbconfig_feature = $LOADED_FEATURES.find { |f| f.end_with?('/rbconfig.rb') }
stdlib_prefix = rbconfig_feature && "#{File.dirname(File.dirname(rbconfig_feature))}/"
purged = $LOADED_FEATURES.reject! do |f|
  f.start_with?(pwd_prefix) && !(stdlib_prefix && f.start_with?(stdlib_prefix))
end
STDERR.puts "[compile] purged $LOADED_FEATURES entries under #{pwd_prefix} (#{(purged ? $LOADED_FEATURES.size : 'none')} remaining)"

# Re-executing PSDK class definitions emits "already initialized constant"
# warnings on every reopened constant — harmless but noisy enough to bury
# real compile output. $VERBOSE=nil silences them for the require block
# only and is restored on the way out, even if require raises.
STDERR.puts "[compile] loading Game.rb with #{util_flag}"
old_verbose = $VERBOSE
$VERBOSE = nil
begin
  require './Game.rb'
ensure
  $VERBOSE = old_verbose
  ArchiveMount.unmount! # cleanup
end
STDERR.puts "[compile] ProjectCompilation finished"
