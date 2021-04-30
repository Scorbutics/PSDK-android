#ifndef CGSS_FRAMED_VIEW_H
#define CGSS_FRAMED_VIEW_H
#include <optional>
#include <array>
#include "View.h"
#include "ViewAuthorizations.h"
#include "LiteCGSS/Graphics/Texture.h"
#include "LiteCGSS/Graphics/Drawable.h"

#include "LiteCGSS/Events/MainEventDispatcher.h"
#include "LiteCGSS/Events/Patterns/SubObserver.h"
#include "ViewBox.h"
#include "Viewport.h"

namespace cgss {

	class FramedView;
	class FramedViewItem :
		public GraphicsStackItem,
		public View,
		public ViewBox,
		private SubObserver<ViewportChangeEvent> {
		friend class FramedView;

	private:
		void onMove() override final;
		void onResize() override final;

		void updateCursorSprite();

		void updatePauseSprite();
		void resetPausePosition();

		void updateContents();
		void updateBackOpacity();
		void updateContentsOpacity();

		DisplayWindowUserData m_windowData;
		std::vector<sf::VertexArray> m_vertices;
		sf::Texture* m_texture = nullptr;
		sf::Sprite m_pause;
		sf::Sprite m_cursor;

		long m_counter = 0;

		bool m_locked = false;
		bool m_paused = false;
		bool m_stretch = false;

		std::weak_ptr<ViewportItem> m_parent;

		sf::IntRect m_cursorRect;

		// Array that describe how the frame is defined on the window skin (setted by the "setSkin" function member).
		// Defined this way : [frame_mid_x, frame_mid_y, frame_mid_width, frame_mid_height, contents_ox, contents_oy]
		// Last 2 elements are optional offset_x and offset_y of right and bottom sides.
		std::array<long, 8> m_windowBuilder = {};
		bool m_windowBuilderExtended = false;

		long m_wantedWidth = 1;
		long m_wantedHeight = 1;

		float m_x = 0;
		float m_y = 0;
		float m_wantedOx = 0;
		float m_wantedOy = 0;

		std::uint8_t m_opacity = 255;
		std::uint8_t m_backOpacity = 255;
		std::uint8_t m_contentOpacity = 255;
		bool m_active = false;
		std::optional<long> m_pauseX;
		std::optional<long> m_pauseY;
		Rectangle* m_bondRectViewport = nullptr;

		bool onViewportChange(ViewportChangeEvent& event);
		void updateVerticesBlt(long wt, long ht);
		void updateVerticesStretch(long wt, long ht);
		void allocateVerticesBlt(long delta_w, long nb2, long delta_h, long nb4);
		void calculateVertices(long x, long y, long line, long cell, sf::Vector2i& a, sf::IntRect& rect);
		void allocateVerticesStretch();
		void calculateVerticesStretch(long x, long y, long line, long cell, sf::Vector2i& s, sf::Vector2i& a, sf::IntRect& rect);
		void updateVertices();
		void updateView();

	public:
		FramedViewItem(FramedView* owner, MainEventDispatcher& eventDispatcher, DisplayWindowUserData windowData, const DisplayWindowSettings& windowSettings, std::weak_ptr<ViewportItem> parent = {});
		FramedViewItem(FramedViewItem&&) noexcept = delete;
		FramedViewItem& operator=(FramedViewItem&&) noexcept = delete;
		FramedViewItem(const FramedViewItem&) = delete;
		FramedViewItem& operator=(const FramedViewItem&) = delete;

		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void drawCalculateView(ViewportItem& parent, sf::View& targetView) const;
		void update();

		void setOpacity(uint8_t opacity) override;
		uint8_t getOpacity() const;

		void setContentsOpacity(uint8_t opacity);
		uint8_t getContentsOpacity() const;

		void setBackOpacity(uint8_t opacity);
		uint8_t getBackOpacity() const;

		void lock();
		void unlock();
		bool isLocked() const;

		void pause(bool pause);
		bool isPaused() const;

		void active(bool active);
		bool isActive() const;

		void setSkin(sf::Texture* skin);
		sf::Texture* getSkin() const;

		long getPauseX() const;
		long getPauseY() const;

		void setPauseSkin(sf::Texture& texture);
		void setPausePosition(float x, float y);

		void setCursorSkin(sf::Texture& texture);
		void setCursorRectangle(sf::IntRect position);

		void bindRectangleViewport(Rectangle* output);
		const sf::IntRect& getCursorRectangle() const;

		void stretch(bool stretch);
		bool isStretched() const;

		void setBuilder(std::array<long, 6> windowBuilder);
		void setBuilder(std::array<long, 8> windowBuilder);

		void move(float x, float y);
		void updateFromValue(const sf::IntRect* rectangle) override;
	};

	class FramedView :
		public Drawable<FramedView, FramedViewItem>,
		public GraphicItem {
	public:
		FramedView() = default;
		virtual ~FramedView() = default;

		FramedView(FramedView&&) noexcept = default;
		FramedView& operator=(FramedView&&) noexcept = default;
		FramedView(const FramedView&) = delete;
		FramedView& operator=(const FramedView&) = delete;

		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void sortZ();

		void lock();
		void unlock();
		bool isLocked() const;

		void move(float x, float y);
		void setAngle(float angle);
		void moveOrigin(long ox, long oy);
		void resize(long width, long height);

		void setOpacity(uint8_t opacity);
		uint8_t getOpacity() const;

		void setBackOpacity(uint8_t opacity);
		uint8_t getBackOpacity() const;

		void setContentsOpacity(uint8_t opacity);
		uint8_t getContentsOpacity() const;

		void pause(bool pause);
		bool isPaused() const;

		void active(bool active);
		bool isActive() const;

		void setSkin(sf::Texture* skin);
		sf::Texture* getSkin() const;

		long getPauseX() const;
		long getPauseY() const;

		void setPauseSkin(sf::Texture& texture);
		void setPausePosition(float x, float y);

		void setCursorSkin(sf::Texture& texture);
		void setCursorRectangle(sf::IntRect position);
		void setBuilder(std::array<long, 6> windowBuilder);
		void setBuilder(std::array<long, 8> windowBuilder);

		const sf::IntRect& getCursorRectangle() const;

		void stretch(bool stretch);
		bool isStretched() const;

		void update();
		sf::IntRect getViewportBox() const;
		long getOx() const;
		long getOy() const;

		float getWidth() const;
		float getHeight() const;
		float getX() const;
		float getY() const;

		template <class View, class StackItem, class Drawable>
		StackItem& add(std::shared_ptr<StackItem> d) {
			return m_proxy->View::add<FramedView, StackItem, Drawable>(std::move(d));
		}

		template <class View, class ... Args>
		View addView(Args&& ... args) {
			return View::create(*this, m_proxy->m_windowData.eventDispatcher, m_proxy->m_windowData, m_proxy->m_windowData.settings, std::forward<Args>(args)...);
		}

		void bindRectangleViewport(Rectangle* output);
	private:
		void onDispose() override { m_proxy->View::drawables().clear(); }
	};

	class Sprite;
	class Text;
	class Shape;

	template <>
	struct ViewAuthorizations<FramedView> : public AuthorizedDrawableSet<Sprite, Text, Shape> {};
}

#endif