require 'rubygems'
puts "rubygems loaded with success"

t = Time.now()
puts t.strftime("Time is %m/%d/%y %H:%M")
puts "Testing the LiteRGSS engine validity"
require 'libLiteRGSS'
puts "LiteRGSS engine is valid"