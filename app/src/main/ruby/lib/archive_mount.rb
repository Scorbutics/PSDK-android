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

require 'monitor'

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

  # A mount at another point waiting for the other users checks this often
  # whether they ended (a thread that ended never released its hold)
  USERS_CHECK_INTERVAL = 1

  # The archive's mount is process-wide (PhysFS) and shared by interpreters
  # running at the same time (the splash's extract_loader_assets.rb and a
  # compile): each holds it until its unmount!, the last one unmounts it. An
  # interpreter runs its scripts on one Ruby thread: the thread is the user.
  @lock = Monitor.new
  @released = @lock.new_cond
  @users = []
  @mount_point = nil
  @source = nil

  def mounted?(mount_point = '/')
    MOUNT_PROBES.any? { |probe| PhysFS.exist?(File.join(mount_point, probe)) }
  end

  def plain?
    ENV[ENV_ENC_KEY].to_s.empty?
  end

  # The archive at mount_point, held by this thread until its unmount!. When
  # other threads hold it at another point, waits until they release it.
  def mount!(mount_point = '/')
    @lock.synchronize do
      if @mount_point && @mount_point != mount_point
        moving = -> { @mount_point && @mount_point != mount_point }
        @released.wait(USERS_CHECK_INTERVAL) while moving.call && other_users?
        # Meanwhile the last of them may have unmounted it, or another script
        # mounted it again where it is wanted
        unmount_archive if moving.call
        raise "Archive still mounted at #{@mount_point}, wanted at #{mount_point}" if moving.call
      end
      mount_archive(mount_point) unless @mount_point
      @users << Thread.current unless @users.include?(Thread.current)
    end
  end

  # Releases this thread's hold; the last user unmounts the archive (PhysFS
  # knows a mount by its source, not by its mount point).
  def unmount!
    @lock.synchronize do
      @users.delete(Thread.current)
      @users.select!(&:alive?)
      unmount_archive if @users.empty? && @mount_point
      @released.broadcast
    end
  end

  def other_users?
    @users.select!(&:alive?)
    (@users - [Thread.current]).any?
  end

  def mount_archive(mount_point)
    path = ENV[ENV_PATH] or raise "Missing #{ENV_PATH} env var"
    if plain?
      PhysFS.mount(path, mount_point, false)
      @source = path
    else
      enc_hex = ENV[ENV_ENC_KEY]
      mac_hex = ENV[ENV_MAC_KEY].to_s
      raise "Missing #{ENV_MAC_KEY} env var" if mac_hex.empty?

      io = EpsaStream.new(path, enc_key: [enc_hex].pack('H*'), mac_key: [mac_hex].pack('H*'))
      PhysFS.mount_io(io, EPSA_MOUNT_NAME, mount_point, false)
      @source = EPSA_MOUNT_NAME
    end
    @mount_point = mount_point
  end

  # The source it was mounted from (the environment is process-wide: another
  # interpreter may have changed it since). Still mounted when PhysFS keeps
  # it (a file of it still open): the state says so.
  def unmount_archive
    PhysFS.unmount(@source)
    @mount_point = @source = nil
    @users.clear
  rescue PhysFS::Error => e
    warn "[ArchiveMount] #{@source} not unmounted: #{e.message}"
  end
  private_class_method :other_users?, :mount_archive, :unmount_archive
end

end # unless defined?(ArchiveMount::ENV_PATH)
