# Standalone splash orchestrator.
#
# All implementation lives in loader/*.rb. The Android side can't
# resolve require_relative (the script is `ruby -e`'d via AssetManager
# String content, so __FILE__ is "-e"; the helper files aren't on the
# filesystem or in a PhysFS mount), so RubyScript concatenates the
# helpers as preludes before this file — see RootActivity.kt's
# LOADER_PRELUDES for the order. By the time this script runs, the
# whole Loader::* namespace is defined.
#
# Reads project metadata / icon / font from <pwd>/loader_assets/ —
# files extract_loader_assets.rb wrote during archive validation. That
# means this script has zero archive dependency: no PhysFS, no
# ArchiveMount, no EpsaStream. The mount is owned by check_engine.rb
# on the compile side.
#
# Lifecycle:
#   1. Loader::Splash.new + show! + run_intro.
#   2. Optional CTA flash (only if no real status arrived during the
#      intro hold).
#   3. Tick the animation forever, bailing when the window is closed
#      (e.g. activity destroyed) — the activity tears the loader
#      interpreter down by closing it.

$loader_screen = Loader::Splash.new

# `ensure` is load-bearing AND must wrap the ENTIRE post-construction
# body, not just the final loop. When the activity drives a workload
# swap (project click → setLoaderState restart), it pushes
# sf::Event::Closed into the loader's DisplayWindow queue. Any of the
# update-driving calls below (show!, run_intro, hold_seconds, tick)
# will raise LiteRGSS::DisplayWindow::ClosedWindowError from inside
# `update` — including the hold_seconds in the splash's pre-loop CTA
# logic. If only the loop were wrapped, an exception raised during
# hold_seconds would unwind past the begin (never entering it) and
# $loader_screen would leak to KMP's destroyInterpreter on the UI
# thread. The destructor would then call setActive(false) on the
# wrong thread (UI, not the Ruby render thread that owns the EGL
# context) — releasing nothing, leaving the context bound to the
# dying render thread, causing the next loader's render thread to
# loop forever on EGL_BAD_ACCESS.
#
# With this wider ensure, dispose runs on THIS render thread mid-
# unwind regardless of which call raised. setActive(false) executes
# on the context-owning thread, EGL releases cleanly, the log-flush
# sentinel arrives promptly, and destroyInterpreter returns instead
# of timing out at 5s (which is what was producing the ANR).
begin
  $loader_screen.show!
  $loader_screen.run_intro

  # Decorative status progression — the splash isn't gated on real
  # compile state (the activity tears the loader interpreter down on
  # destroy), so the CTA just means "intro finished, we've been on
  # Initializing for a few seconds". hold_seconds returns false if the
  # window closes mid-hold, which propagates into the final tick loop
  # bailing immediately. Copy intentionally mirrors the import card's
  # removed "Start Compilation" button — the icon is now the tap target.
  #
  # Gated on external_status_received? so the CTA never clobbers a real
  # sub-step label. Classic-bootstrap's auto-tap fires before this point
  # and the compile engine's first onStepStarted ("Check engine…")
  # arrives via the .loader_status poll inside hold_seconds, which sets
  # the flag — so the CTA is skipped entirely in classic mode. Same
  # protection covers fast super-user taps where the user races the
  # 3-second hold.
  if $loader_screen.hold_seconds(Loader::Splash::INIT_TO_READY_SECS) &&
     !$loader_screen.external_status_received?
    $loader_screen.set_status('< Tap logo to compile >')
  end

  loop { break unless $loader_screen.tick }
ensure
  $loader_screen&.dispose rescue nil
end
