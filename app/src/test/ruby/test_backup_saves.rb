# Unit tests for backup_saves.rb — the "Backing up previous Release" compile step.
#
# The script is a top-level one-shot working on the cwd, so each test loads it
# inside a fresh temporary directory. Runs on plain MRI.
#
# Run:  ruby app/src/test/ruby/test_backup_saves.rb

require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'

SCRIPT = File.expand_path('../../main/ruby/backup_saves.rb', __dir__)

class TestBackupSaves < Minitest::Test
  def setup
    @tmp = Dir.mktmpdir('backup_saves')
  end

  def teardown
    FileUtils.rm_rf(@tmp)
  end

  def run_script
    Dir.chdir(@tmp) do
      # The script writes to the STDERR constant: capture at the fd level
      capture_subprocess_io { load SCRIPT }
    end
  end

  def write(path, content)
    full = File.join(@tmp, path)
    FileUtils.mkdir_p(File.dirname(full))
    File.write(full, content)
  end

  def read(path)
    File.read(File.join(@tmp, path))
  end

  def exist?(path)
    File.exist?(File.join(@tmp, path))
  end

  def test_release_is_moved_to_backup
    write('Release/Saves/Pokemon_Party-1', 'user save')

    run_script

    refute exist?('Release')
    assert_equal 'user save', read('ReleaseBackup/Saves/Pokemon_Party-1')
  end

  def test_nothing_to_back_up
    run_script

    refute exist?('Release')
    refute exist?('ReleaseBackup')
  end

  # Regression: a compilation killed after this step left ReleaseBackup (the only copy
  # of the user saves) next to a partial Release. The next run used to delete the backup
  # and back up the partial Release instead, losing the saves.
  def test_interrupted_compilation_keeps_backup_and_drops_partial_release
    write('ReleaseBackup/Saves/Pokemon_Party-1', 'user save')
    write('Release/Data/partial.dat', 'partial build')

    run_script

    refute exist?('Release')
    assert_equal 'user save', read('ReleaseBackup/Saves/Pokemon_Party-1')
  end

  def test_interrupted_compilation_without_release
    write('ReleaseBackup/Saves/Pokemon_Party-1', 'user save')

    run_script

    refute exist?('Release')
    assert_equal 'user save', read('ReleaseBackup/Saves/Pokemon_Party-1')
  end
end
