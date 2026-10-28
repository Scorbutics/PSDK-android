# Mounts the game archive at the PhysFS root: an encrypted .epsa via a
# streaming decrypter so the plaintext ZIP never lands on the filesystem,
# or a plain ZIP (.psa / .zip) directly.
#
# Inputs (set by PsdkInterpreter on the JVM side):
#   PSDK_EPSA_PATH        — absolute path to the archive
#   PSDK_EPSA_KEY_HEX     — hex-encoded K_enc (32 bytes → 64 chars), empty
#                           for a plain ZIP
#   PSDK_EPSA_MAC_KEY_HEX — hex-encoded K_mac (32 bytes → 64 chars), empty
#                           for a plain ZIP
#
# The file path remains visible to anyone with adb access to /data/data, but
# it now points at the *encrypted* bundle. Without K_enc / K_mac (which only
# exist in process memory for the duration of the compile run), the bytes
# are useless.

# Guard the whole module body — same rationale as epsa_format.rb / epsa_stream.rb.
# ARCHIVE_PRELUDES prepends this file to every compile-VM script, so on the
# reused VM it gets evaluated 3+ times per compile flow. Probe ENV_PATH (the
# first internal const) instead of the module name, which gets autodefined on
# first reopen.
unless defined?(ArchiveMount::ENV_PATH)

module ArchiveMount
  module_function

  ENV_PATH    = 'PSDK_EPSA_PATH'.freeze
  ENV_ENC_KEY = 'PSDK_EPSA_KEY_HEX'.freeze
  ENV_MAC_KEY = 'PSDK_EPSA_MAC_KEY_HEX'.freeze

  # Cheap "is the archive already mounted at mount_point" probe. Project
  # sources ship PSDK's build marker at the first path, a compiled release
  # (which packs it into Data/3.dat) its bytecode at the second.
  MOUNT_PROBES = ['Data/Studio/psdk.dat', 'Game.yarb'].freeze

  # Name PhysFS knows an .epsa mount by (mount_io has no real path).
  EPSA_MOUNT_NAME = 'archive.zip'.freeze

  def mounted?(mount_point = '/')
    MOUNT_PROBES.any? { |probe| PhysFS.exist?(File.join(mount_point, probe)) }
  end

  def plain?
    ENV[ENV_ENC_KEY].to_s.empty?
  end

  def mount!(mount_point = '/')
    return if mounted?(mount_point)

    path = ENV[ENV_PATH] or raise "Missing #{ENV_PATH} env var"
    return PhysFS.mount(path, mount_point, false) if plain?

    enc_hex = ENV[ENV_ENC_KEY]
    mac_hex = ENV[ENV_MAC_KEY].to_s
    raise "Missing #{ENV_MAC_KEY} env var" if mac_hex.empty?

    enc_key = [enc_hex].pack('H*')
    mac_key = [mac_hex].pack('H*')

    io = EpsaStream.new(path, enc_key: enc_key, mac_key: mac_key)
    PhysFS.mount_io(io, EPSA_MOUNT_NAME, mount_point, false)
  end

  # Unmounts wherever the archive is mounted (PhysFS knows a mount by its
  # source, not by its mount point).
  def unmount!
    return if ENV[ENV_PATH].to_s.empty?

    PhysFS.unmount(plain? ? ENV[ENV_PATH] : EPSA_MOUNT_NAME)
  rescue PhysFS::Error
    nil
  end
end

end # unless defined?(ArchiveMount::ENV_PATH)
