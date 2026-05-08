# Mounts the encrypted .epsa archive at the PhysFS root via a streaming
# decrypter so the plaintext ZIP never lands on the filesystem.
#
# Inputs (set by PsdkInterpreter on the JVM side):
#   PSDK_EPSA_PATH        — absolute path to the .epsa file
#   PSDK_EPSA_KEY_HEX     — hex-encoded K_enc (32 bytes → 64 chars)
#   PSDK_EPSA_MAC_KEY_HEX — hex-encoded K_mac (32 bytes → 64 chars)
#
# The file path remains visible to anyone with adb access to /data/data, but
# it now points at the *encrypted* bundle. Without K_enc / K_mac (which only
# exist in process memory for the duration of the compile run), the bytes
# are useless.

module ArchiveMount
  module_function

  ENV_PATH    = 'PSDK_EPSA_PATH'.freeze
  ENV_ENC_KEY = 'PSDK_EPSA_KEY_HEX'.freeze
  ENV_MAC_KEY = 'PSDK_EPSA_MAC_KEY_HEX'.freeze

  def mount!
    epsa_path = ENV[ENV_PATH]   or raise "Missing #{ENV_PATH} env var"
    enc_hex   = ENV[ENV_ENC_KEY] or raise "Missing #{ENV_ENC_KEY} env var"
    mac_hex   = ENV[ENV_MAC_KEY] or raise "Missing #{ENV_MAC_KEY} env var"

    enc_key = [enc_hex].pack('H*')
    mac_key = [mac_hex].pack('H*')

    io = EpsaStream.new(epsa_path, enc_key: enc_key, mac_key: mac_key)
    PhysFS.mount_io(io, 'archive.zip', '/', false)
  end
end
