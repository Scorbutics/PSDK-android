# Unit tests for ArchiveMount — the archive's mount, shared by the scripts of
# the interpreters running at the same time (one Ruby thread each).
#
# Run:  ruby app/src/test/ruby/test_archive_mount.rb

require 'minitest/autorun'

# PhysFS as far as ArchiveMount uses it: the mounts, by source
module PhysFS
  class Error < StandardError; end

  class << self
    # busy: a file of the archive still open, it can't be unmounted
    attr_accessor :mounts, :busy

    def mount(path, mount_point, _append) = (mounts[source(path)] = mount_point)
    def mount_io(_io, name, mount_point, _append) = (mounts[source(name)] = mount_point)

    # The gem takes strings only (TypeError otherwise)
    def source(name) = name.is_a?(String) ? name : raise(TypeError, "#{name.inspect} is not a String")

    def unmount(source)
      raise Error, 'files still open' if busy

      mounts.delete(source(source)) || raise(Error, "#{source} is not mounted")
    end
  end
end

class EpsaStream
  def initialize(_path, enc_key:, mac_key:); end
end

require File.expand_path('../../main/ruby/lib/archive_mount', __dir__)

class TestArchiveMount < Minitest::Test
  def setup
    PhysFS.mounts = {}
    PhysFS.busy = false
    ENV[ArchiveMount::ENV_PATH] = '/data/archive.staging'
    ENV[ArchiveMount::ENV_ENC_KEY] = 'aa' * 32
    ENV[ArchiveMount::ENV_MAC_KEY] = 'bb' * 32
  end

  def teardown
    ArchiveMount.unmount!
    ArchiveMount.instance_variable_get(:@users).clear
    ArchiveMount.instance_variable_set(:@mount_point, nil)
    ArchiveMount.instance_variable_set(:@source, nil)
  end

  # Runs each step on its own thread, the threads kept alive (an interpreter's
  # worker) until the test ends
  class Script
    def initialize
      @steps = Queue.new
      @done = Queue.new
      @thread = Thread.new do
        while (step = @steps.pop)
          @done << begin
            step.call
          rescue StandardError => e
            e
          end
        end
      end
    end

    def run(&step)
      @steps << step
      result
    end

    def start(&step) = @steps << step

    # What the step returned, its exception raised here
    def result
      value = @done.pop
      raise value if value.is_a?(Exception)

      value
    end
    def done? = !@done.empty?

    def stop
      @steps << nil
      @thread.join
    end
  end

  # Nothing warned: a warning is a failure the scripts would not see
  def quietly(&block)
    _, err = capture_io(&block)
    assert_empty err
  end

  def scripts(count)
    list = Array.new(count) { Script.new }
    yield(*list)
  ensure
    list.each(&:stop)
  end

  def test_the_last_user_unmounts_the_archive_whoever_mounted_it
    quietly { last_user_unmounts }
  end

  def last_user_unmounts
    [%i[splash compile], %i[compile splash]].each do |first, second|
      scripts(2) do |splash, compile|
        users = { splash: splash, compile: compile }
        users[first].run { ArchiveMount.mount! }
        users[second].run { ArchiveMount.mount! }
        splash.run { ArchiveMount.unmount! }
        assert_equal({ 'archive.zip' => '/' }, PhysFS.mounts, "#{first} first: the compile still reads it")
        compile.run { ArchiveMount.unmount! }
        assert_empty PhysFS.mounts
      end
    end
  end

  def test_a_script_mounting_twice_releases_once
    scripts(1) do |compile|
      compile.run { ArchiveMount.mount! }
      compile.run { ArchiveMount.mount! }
      compile.run { ArchiveMount.unmount! }
      assert_empty PhysFS.mounts
    end
  end

  def test_a_mount_elsewhere_waits_for_the_other_users
    quietly { mount_elsewhere_waits }
  end

  def mount_elsewhere_waits
    scripts(2) do |splash, compile|
      splash.run { ArchiveMount.mount! }
      compile.run { ArchiveMount.mount! }
      compile.run { ArchiveMount.unmount! }
      compile.start { ArchiveMount.mount!('/install') }
      sleep 0.2
      refute compile.done?, 'did not wait'
      assert_equal({ 'archive.zip' => '/' }, PhysFS.mounts, 'moved under the splash')
      splash.run { ArchiveMount.unmount! }
      compile.result
      assert_equal({ 'archive.zip' => '/install' }, PhysFS.mounts)
    end
  end

  def test_a_finished_script_holds_nothing
    Thread.new { ArchiveMount.mount! }.join
    scripts(1) do |compile|
      compile.run { ArchiveMount.mount!('/install') }
      assert_equal({ 'archive.zip' => '/install' }, PhysFS.mounts)
      compile.run { ArchiveMount.unmount! }
      assert_empty PhysFS.mounts
    end
  end

  def test_scripts_waiting_for_the_same_move_share_it
    quietly do
      scripts(3) do |splash, first, second|
        splash.run { ArchiveMount.mount! }
        first.start { ArchiveMount.mount!('/install') }
        second.start { ArchiveMount.mount!('/install') }
        sleep 0.2
        splash.run { ArchiveMount.unmount! }
        # One moves it, the other finds it where it wants it: no raise
        first.result
        second.result
        assert_equal({ 'archive.zip' => '/install' }, PhysFS.mounts)
        first.run { ArchiveMount.unmount! }
        assert_equal({ 'archive.zip' => '/install' }, PhysFS.mounts, 'still held by the second')
        second.run { ArchiveMount.unmount! }
        assert_empty PhysFS.mounts
      end
    end
  end

  def test_a_script_moves_its_own_mount
    quietly do
      scripts(1) do |compile|
        compile.run { ArchiveMount.mount! }
        compile.run { ArchiveMount.mount!('/install') }
        assert_equal({ 'archive.zip' => '/install' }, PhysFS.mounts)
        compile.run { ArchiveMount.unmount! }
        assert_empty PhysFS.mounts
      end
    end
  end

  def test_a_user_that_ended_without_releasing_holds_nothing
    Thread.new { ArchiveMount.mount! }.join
    scripts(1) do |splash|
      splash.run { ArchiveMount.mount! }
      splash.run { ArchiveMount.unmount! }
      assert_empty PhysFS.mounts
    end
  end

  def test_an_archive_still_in_use_stays_mounted_and_is_not_moved
    ArchiveMount.mount!
    PhysFS.busy = true
    _, err = capture_io { ArchiveMount.unmount! }
    assert_match(/not unmounted: files still open/, err)
    assert_equal({ 'archive.zip' => '/' }, PhysFS.mounts)
    capture_io { assert_raises(RuntimeError) { ArchiveMount.mount!('/install') } }
    PhysFS.busy = false
  end

  def test_nothing_mounted_nothing_to_unmount
    ArchiveMount.unmount!
    assert_empty PhysFS.mounts
  end

  def test_a_plain_archive_is_mounted_by_its_path
    ENV[ArchiveMount::ENV_ENC_KEY] = ''
    ArchiveMount.mount!
    assert_equal({ '/data/archive.staging' => '/' }, PhysFS.mounts)
    ArchiveMount.unmount!
    assert_empty PhysFS.mounts
  end
end
