begin
    fifo_command = ENV['ANDROID_FIFO_COMMAND_INPUT']
    fifo_return = ENV['ANDROID_FIFO_COMMAND_OUTPUT']

    loop do
        while !File.exists?(fifo_command) do
            puts "Waiting for fifo"
            sleep 1
        end
        begin
            content = File.read(fifo_command)
        rescue IOError => error
            puts error
        end

        begin
            lambda do
                eval content + "\n"
            end.call
            File.open(fifo_return, "w") { |output|
              output.write("0\n")
              output.flush
            }
        rescue Exception => error
            STDERR.puts error
            File.open(fifo_return, "w") { |output|
              output.write("1\n")
              output.flush
            }
        end
    end
rescue Exception => error
    STDERR.puts error
    STDERR.puts error.backtrace.join("\n\t")
end
