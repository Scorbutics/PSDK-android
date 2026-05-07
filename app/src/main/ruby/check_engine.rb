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
# as a confusing NoMethodError mid-mount.
required_methods = %i[mount unmount write_dir= write_dir exist? directory? mtime read enumerate glob]
missing = required_methods.reject { |m| PhysFS.respond_to?(m) }
raise "PhysFS is missing required methods: #{missing.inspect}" unless missing.empty?

shim_methods = %i[install_shim! uninstall_shim! shim_installed?]
missing_shim = shim_methods.reject { |m| PhysFS.respond_to?(m) }
raise "PhysFS shim API is missing methods: #{missing_shim.inspect}" unless missing_shim.empty?

# Side-effect-free C call to prove the bindings actually execute (not just
# that the symbols are registered). No mount has happened yet, so write_dir
# must be nil and the shim must be dormant.
unless PhysFS.write_dir.nil?
  raise "PhysFS.write_dir should be nil before any mount, got #{PhysFS.write_dir.inspect}"
end
raise "PhysFS shim should be dormant before any mount" if PhysFS.shim_installed?

puts "physfs gem is valid"
