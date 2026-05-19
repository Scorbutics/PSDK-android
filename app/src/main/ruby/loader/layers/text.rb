module Loader
  module Layers
    # Caption-style text stack centered on the icon:
    #
    #   [Game Title]            ← TITLE_FONT_SIZE, white, full outline
    #     v1.2.3                ← VERSION_FONT_SIZE, icon-tinted
    #     [ICON]                ← centered on canvas (Layers::Icon)
    #   ‹tap logo to compile›   ← CTA_FONT_SIZE, accent-tinted
    #            …
    #   Powered by PSDK · …     ← FOOTER_FONT_SIZE, canvas bottom
    #
    # The status line ("‹ Initializing… ›") doubles as the externally-
    # driven progress display — StatusFile calls #update_status as the
    # compile wizard writes new sub-step labels.
    #
    # The footer renders in every state, including the no-icon default
    # where the splash is just background + particles. Title / version
    # / status are positioned relative to the icon and only built when
    # one is present.
    class Text
      TITLE_FONT_SIZE   = 20
      VERSION_FONT_SIZE = 11
      CTA_FONT_SIZE     = 12
      FOOTER_FONT_SIZE  = 9

      def initialize(project_info, font_loaded:)
        @project_info = project_info
        @font_loaded  = font_loaded
      end

      def build(viewport, window_width, window_height, icon)
        return unless @font_loaded   # without a font, Text.new raises
        full_w = window_width

        # The GL surface draws edge-to-edge under the system navigation
        # bar, so the bottom margin has to clear the nav bar's logical-
        # pixel height on top of the visual gap. ~56 px covers the
        # 3-button bar on typical xxhdpi/xxxhdpi phones at the 240px-
        # short-edge logical canvas; gesture-nav devices end up with
        # extra padding, which still reads as comfortable bottom
        # whitespace.
        footer_y = window_height - 56
        @footer = LiteRGSS::Text.new(0, viewport, 0, footer_y, full_w, 12,
                                     'Powered by PSDK · LiteRGSS2 · Ruby VM', 1)
        @footer.size          = FOOTER_FONT_SIZE
        @footer.fill_color    = LiteRGSS::Color.new(110, 125, 150, 200)
        @footer.outline_color = LiteRGSS::Color.new(0, 0, 0, 140)
        @footer.opacity       = 0
        @footer.z             = 20

        return unless icon.present?

        cy     = icon.center_y
        half_h = icon.icon_h / 2
        accent = icon.cta_accent_color

        # Visual hierarchy from font size: title largest, footer
        # smallest. Title box grew with TITLE_FONT_SIZE (16 → 20);
        # offset bumped by the same 4 px so version_y still sits 4 px
        # below the title's bottom edge.
        title_y   = cy - half_h - 60
        version_y = cy - half_h - 28
        cta_y     = cy + half_h + 8

        # Cache the resting y of each lift-able element so animate can
        # derive the per-frame y by subtracting state.lift_offset_for.
        # The footer doesn't lift — it stays anchored to the canvas
        # bottom while the result card slides up underneath it.
        @title_base_y   = title_y
        @version_base_y = version_y
        @status_base_y  = cta_y

        @title = LiteRGSS::Text.new(0, viewport, 0, title_y, full_w, 28, @project_info[:title], 1)
        @title.size              = TITLE_FONT_SIZE
        @title.fill_color        = LiteRGSS::Color.new(245, 245, 250, 255)
        @title.outline_color     = LiteRGSS::Color.new(0, 0, 0, 220)
        @title.outline_thickness = 2
        @title.opacity           = 0
        @title.z                 = 20

        @version = LiteRGSS::Text.new(0, viewport, 0, version_y, full_w, 18, @project_info[:version], 1)
        @version.size          = VERSION_FONT_SIZE
        @version.fill_color    = LiteRGSS::Color.new(accent[0], accent[1], accent[2], 230)
        @version.outline_color = LiteRGSS::Color.new(0, 0, 0, 180)
        @version.opacity       = 0
        @version.z             = 20

        # Caption-style CTA. "‹" and "›" are common single-guillemet
        # glyphs; they fall back to a missing-glyph box on fonts
        # without them. The PokemonDS.ttf shipped by most PSDK projects
        # covers them.
        @status = LiteRGSS::Text.new(0, viewport, 0, cta_y, full_w, 16,
                                     "‹ Initializing… ›", 1)
        @status.size              = CTA_FONT_SIZE
        @status.fill_color        = LiteRGSS::Color.new(accent[0], accent[1], accent[2], 255)
        @status.outline_color     = LiteRGSS::Color.new(0, 0, 0, 220)
        @status.outline_thickness = 1
        @status.opacity           = 0
        @status.z                 = 20
      end

      def animate(clock, state, m)
        # Per-element lift offset. Staggers cascade: title leads (small
        # delay vs. icon), version chases, status trails — gives a
        # "things following the icon up" feel rather than a rigid
        # block-slide.
        title_lift   = state.lift_offset_for(SceneState::LIFT_DELAY_TITLE, clock.frame)
        version_lift = state.lift_offset_for(SceneState::LIFT_DELAY_VERSION, clock.frame)
        status_lift  = state.lift_offset_for(SceneState::LIFT_DELAY_STATUS, clock.frame)

        title_t = Util.ease_out_cubic(((clock.frame - 24) / 44.0).clamp(0.0, 1.0))
        if @title && !@title.disposed?
          @title.opacity = (255 * title_t * m).to_i
          @title.y = (@title_base_y - title_lift).to_i if @title_base_y
        end

        ver_t = Util.ease_out_cubic(((clock.frame - 44) / 44.0).clamp(0.0, 1.0))
        if @version && !@version.disposed?
          @version.opacity = (235 * ver_t * m).to_i
          @version.y = (@version_base_y - version_lift).to_i if @version_base_y
        end

        status_t = Util.ease_out_cubic(((clock.frame - 60) / 36.0).clamp(0.0, 1.0))
        if @status && !@status.disposed?
          @status.opacity = (255 * status_t * m).to_i
          @status.y = (@status_base_y - status_lift).to_i if @status_base_y
        end

        footer_t = Util.ease_out_cubic(((clock.frame - 84) / 56.0).clamp(0.0, 1.0))
        if @footer && !@footer.disposed?
          @footer.opacity = (220 * footer_t * m).to_i
        end
      end

      def update_status(text)
        return unless status_present?
        @status.text = text
      end

      def status_present?
        @status && !@status.disposed?
      end

      def dispose
        [@title, @version, @status, @footer].each do |t|
          t.dispose if t && !t.disposed?
        end
        @title = @version = @status = @footer = nil
      end
    end
  end
end
