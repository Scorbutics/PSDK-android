# Unit tests for ScriptErrors — PSDK's error window, replaced by a host event.
#
# Run:  ruby app/src/test/ruby/test_script_errors.rb

require 'minitest/autorun'
require File.expand_path('../../main/ruby/lib/script_errors', __dir__)

# embedded-ruby-vm's host channel, as far as ScriptErrors uses it
module VMHost
  class << self
    attr_accessor :emitted, :connected

    def emit(topic, payload = '')
      return false unless connected

      emitted << [topic, payload]
      true
    end
  end
end

class TestScriptErrors < Minitest::Test
  # As PSDK 26 writes it (Yuki::EXC.build_error_log)
  LOG = "================================Erreur de script================================\r\n" \
        "Message :\r\nundefined method `foo' for nil:NilClass\r\n\r\n" \
        "Type : NoMethodError\r\nScript : 00000 Engine.rb (user)\r\nLigne : 3\r\n" \
        "===================================Backtraces===================================\r\n" \
        "[0] : 00000 Engine.rb (user) | ligne 3 <main>\r\n" \
        "===================================Fin du log===================================".freeze

  def setup
    VMHost.emitted = []
    VMHost.connected = true
  end

  # PSDK's "100 Error Logger.rb", loaded after start.rb installed ScriptErrors
  # (ScriptErrors.install runs once: it is installed once per game process)
  def self.load_psdk
    ScriptErrors.install
    # PSDK takes Yuki::EXC for its loaded scripts (ScriptLoad.rb, Z_main.rb)
    @exc_before_psdk = Yuki.const_defined?(:EXC)
    Object.class_eval <<~RUBY, '100 Error Logger.rb', 1
      module Yuki
        module EXC
          module_function

          def run(log)
            show_error_window(log)
          end

          def show_error_window(log)
            raise 'the game was saved into a picture'
          end
        end

        class ErrorWindow
          def run(error_text, *data_to_add)
            raise 'the window opened'
          end
        end
      end
    RUBY
  end
  load_psdk

  def test_psdk_error_window_replaced_by_the_event
    Yuki::EXC.run(LOG)
    assert_equal [['game.script_error', "NoMethodError: undefined method `foo' for nil:NilClass"]], VMHost.emitted
  end

  def test_psdk_window_replaced_by_the_event
    Yuki::ErrorWindow.new.run(LOG, 'save data')
    assert_equal [['game.script_error', "NoMethodError: undefined method `foo' for nil:NilClass"]], VMHost.emitted
  end

  class << self
    attr_reader :exc_before_psdk
  end

  def test_no_exc_before_psdk
    refute self.class.exc_before_psdk
  end

  def test_summary_of_a_stack_error_log
    log = "Message :\r\nstack level too deep\r\nType : SystemStackError\r\nDate : 03/11/2026\r\n"
    assert_equal 'SystemStackError: stack level too deep', ScriptErrors.summary(log)
  end

  def test_summary_of_another_text
    assert_equal 'Something broke', ScriptErrors.summary("=====\r\n\r\nSomething broke\r\nmore")
    assert_equal '', ScriptErrors.summary(nil)
    assert_equal ScriptErrors::SUMMARY_MAX, ScriptErrors.summary("Message :\n#{'x' * 500}\n").size
  end

  def test_no_host
    VMHost.connected = false
    _, err = capture_io { ScriptErrors.report(LOG) }
    assert_match(/game\.script_error not sent: no host/, err)
  end
end
