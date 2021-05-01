puts "Test !!!"

require "net/http"
require "LiteRGSS"
include LiteRGSS

puts "Fiou..."

w = 1280
h = 720
window = DisplayWindow.new("Memtest", w, h, 2);
@v = Viewport.new(window, w, h)
@v.rect.width = w / 2
@v.rect.height = h / 2

# placeholder for GC stats
b = {}

#> Graphic Test
bmp = Bitmap.new("cb.bmp")
t = Time.new
arr = Array.new(1500000) do #4000 -> down
  sp = Sprite.new(@v)
  sp.bitmap = bmp
  sp.zoom = rand / 2
  sp.set_position(rand(w), rand(h))
  0
end

@running = true
i = 0
window.on_closed = proc do
  @running = false
end

window.update
@b = Sprite.new(window)
t = Time.new
p @b.bitmap = window.snap_to_bitmap
p Time.new - t
t = Time.new
window.freeze

p Time.new - t
arr.clear
t = Time.new
@v = Viewport.new(window, w, h)
puts 'Before GC'
puts GC.stat(b)[:heap_live_slots]
GC.start
puts 'After GC'
puts GC.stat(b)[:heap_live_slots]
p Time.new - t
t = Time.new

@a = Sprite.new(@v)
@a.bitmap = bmp
@a.ox = 75
@a.oy = 60
@a.angle = 45
@a.x = w / 2
@a.y = h / 2
@a.opacity = 200
i = 0

t = Time.new
while @running
  window.update
  @a.angle = (@a.angle + (10 + 10 * Math::cos(i * Math::PI / 20))) % 360
  i += 1
end
dt = (Time.new - t)
p 1 / (dt / i)
@b.bitmap = window.snap_to_bitmap
window.update

p "Waiting..."
sleep 6
p "2nd iteration"

arr = Array.new(1500000) do #4000 -> down
  sp = Sprite.new(@v)
  sp.bitmap = bmp
  sp.zoom = rand / 2
  sp.set_position(rand(w), rand(h))
  0
end

arr.clear
t = Time.new
@v.dispose
p "Before GC"
puts GC.stat(b)[:heap_live_slots]
GC.start
p "After GC"
puts GC.stat(b)[:heap_live_slots]
p Time.new - t

window.dispose
sleep 6
