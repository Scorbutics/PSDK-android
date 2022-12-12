require 'rubygems'
puts "rubygems loaded with success"

begin
  t = Time.now()
  puts t.strftime("Time is %m/%d/%y %H:%M")
  puts "Testing the LiteRGSS engine validity"
  require 'LiteRGSS'
  puts "LiteRGSS engine is valid"
  return 0
rescue => error
  STDERR.puts error
  STDERR.puts error.backtrace.join("\n\t")
end