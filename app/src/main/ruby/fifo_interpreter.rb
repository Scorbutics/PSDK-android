begin
    fifo_command = ENV['ANDROID_FIFO_COMMAND_INPUT']
    fifo_return = ENV['ANDROID_FIFO_COMMAND_OUTPUT']

    while !File.exists?(fifo_return) do
        sleep 1
    end
    loop do
        while !File.exists?(fifo_command) do
            sleep 1
        end
        begin
            content = File.read(fifo_command)
            lambda do
                eval content + "\n"
                STDOUT.flush
                File.open(fifo_return, "w") { |output|
                    output.write("0\n")
                    output.flush
                }
            end.call
        rescue Exception => error
            STDOUT.flush
            STDERR.puts error
            STDERR.puts error.backtrace.join("\n\t")
            File.open(fifo_return, "w") { |output|
                output.write("1\n")
                output.flush
            }
        end
    end

rescue Exception => error
    STDOUT.flush
    STDERR.puts error
    STDERR.puts error.backtrace.join("\n\t")
end
