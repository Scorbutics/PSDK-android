require 'rubygems'
puts "rubygems loaded with success"

t = Time.now()
puts t.strftime("Time is %m/%d/%y %H:%M")

puts "Testing the LiteRGSS engine validity"
require 'LiteRGSS'
puts "LiteRGSS engine is valid"

# `physfs` is statically linked into libembedded-ruby-vm and pre-provided by
# extension-init.c (Init_physfs + rb_provide). The require must therefore be
# a no-op; if it ever raises LoadError, the static link or rb_provide failed.
puts "Testing the physfs gem validity"
require 'physfs'

unless defined?(PhysFS) == 'constant' && PhysFS.is_a?(Module)
  raise "PhysFS module not defined — Init_physfs did not run"
end
unless defined?(PhysFS::Error) == 'constant' && PhysFS::Error < StandardError
  raise "PhysFS::Error class not defined"
end

# Method table must match what compile.rb / copy_saves.rb rely on. A partial
# init (e.g. PhysFSGem_DefineModuleMethods skipped) surfaces here rather than
# as a confusing NoMethodError mid-mount. mount_io is the streaming-decrypt
# entrypoint introduced for v4 .epsa archives — its absence means the gem
# was built against a pre-v4 physfs source.
required_methods = %i[mount mount_io unmount write_dir= write_dir exist? directory? mtime read enumerate glob]
missing = required_methods.reject { |m| PhysFS.respond_to?(m) }
raise "PhysFS is missing required methods: #{missing.inspect}" unless missing.empty?

shim_methods = %i[install_shim! uninstall_shim! shim_installed?]
missing_shim = shim_methods.reject { |m| PhysFS.respond_to?(m) }
raise "PhysFS shim API is missing methods: #{missing_shim.inspect}" unless missing_shim.empty?

# Side-effect-free C call to prove the bindings actually execute (not just
# that the symbols are registered). The splash interpreter (loader.rb)
# shares the process VM but no longer touches PhysFS, so write_dir /
# shim_installed are expected to be at their defaults; we just check
# they're well-typed rather than asserting specific values.
write_dir_value = PhysFS.write_dir
unless write_dir_value.nil? || write_dir_value.is_a?(String)
  raise "PhysFS.write_dir returned an unexpected value: #{write_dir_value.inspect}"
end
shim_value = PhysFS.shim_installed?
unless shim_value == true || shim_value == false
  raise "PhysFS.shim_installed? returned an unexpected value: #{shim_value.inspect}"
end

puts "physfs gem is valid (write_dir=#{write_dir_value.inspect}, shim_installed=#{shim_value})"

# Mount the encrypted .epsa archive at the PhysFS root. compile.rb,
# copy_saves.rb, and backup_saves.rb all assume a live mount — pulling
# the mount into the first compile step keeps that contract enforced in
# one place. ArchiveMount.mount! is idempotent (probes Data/Studio/psdk.dat
# before mounting), so re-runs / retries in the same interpreter are safe.
puts "Mounting encrypted archive"
ArchiveMount.mount!
raise "Archive mount did not produce a usable VFS" unless ArchiveMount.mounted?
puts "Archive mounted"
