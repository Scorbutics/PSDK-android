begin
    fifo_command = ENV['ANDROID_FIFO_COMMAND_INPUT']
    fifo_return = ENV['ANDROID_FIFO_COMMAND_OUTPUT']

    while !File.exists?(fifo_return) do
        puts "Waiting for fifo output"
        sleep 1
    end
    loop do
        while !File.exists?(fifo_command) do
            puts "Waiting for fifo input"
            sleep 1
        end
        begin
            content = File.read(fifo_command)
            lambda do
                eval content + "\n"
                File.open(fifo_return, "w") { |output|
                    output.write("0\n")
                }
            end.call
        rescue Exception => error
            STDERR.puts error
            File.open(fifo_return, "w") { |output|
                output.write("1\n")
            }
        end
    end

rescue Exception => error
    STDERR.puts error
    STDERR.puts error.backtrace.join("\n\t")
end
