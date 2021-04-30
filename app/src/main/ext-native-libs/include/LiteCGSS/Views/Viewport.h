#ifndef CGSS_VIEWPORT_H
#define CGSS_VIEWPORT_H
#include <optional>
#include "LiteCGSS/Graphics/ShaderRenderable.h"
#include "SnapshotCapturable.h"
#include "View.h"
#include "ViewAuthorizations.h"
#include "ViewBox.h"
#include "DisplayWindowUserData.h"
#include "LiteCGSS/Common/Meta/metadata.h"
#include "LiteCGSS/Graphics/RenderStates.h"

namespace cgss {

	class Viewport;
	class ViewportItem :
		public GraphicsStackItem,
		public View,
		public ViewBox,
		public ShaderRenderable {
		friend class Viewport;
	private:
		void setupFullWindowView(sf::RenderTarget& target) const;
		void scaleRenderSpriteToViewport() const;

		DisplayWindowUserData m_windowData;

		std::optional<sf::RenderStates> m_renderStates;
		std::unique_ptr<RenderStates> m_defaultRenderStates;

		double m_wantedOx = 0;
		double m_wantedOy = 0;
	public:
		ViewportItem(Viewport* owner, MainEventDispatcher& eventDispatcher, DisplayWindowUserData windowData, const DisplayWindowSettings& windowSettings);
		void drawFast(sf::RenderTarget& target) const override { draw(target); }
		void draw(sf::RenderTarget& target) const override;

		void updateFromValue(const sf::IntRect* rectangle) override;
		void updateFromValue(const RenderStatesData* renderStatesData) override;
		sf::IntRect getViewportBox() const;
		std::unique_ptr<sf::Texture> takeSnapshot() const;

		const RenderStatesData* getRenderStates() const;
		RenderStatesData* getRenderStates();
	};

	struct DisplayWindowSettings;
	class Viewport :
		public Drawable<Viewport, ViewportItem>,
		public GraphicItem,
		public SnapshotCapturable {

	public:
		Viewport() = default;
		Viewport(Viewport&&) noexcept = default;
		Viewport& operator=(Viewport&&) noexcept = default;
		Viewport(const Viewport&) = delete;
		Viewport& operator=(const Viewport&) = delete;
		virtual ~Viewport();

		void bindRenderStates(RenderStates* states);
		std::unique_ptr<sf::Texture> takeSnapshot() const override;
		void drawFast(sf::RenderTarget& target) const override;
		void draw(sf::RenderTarget& target) const override;
		void sortZ();

		void moveOrigin(long ox, long oy);
		void move(float x, float y);
		void resize(long width, long height);
		void setRectangle(sf::IntRect rectangle);
		sf::IntRect getViewportBox() const;

		long getOx() const;
		long getOy() const;

		void setAngle(float angle);
		void setZoom(float zoom);

		template <class View, class StackItem, class Drawable>
		StackItem& add(std::shared_ptr<StackItem> d) {
			return m_proxy->View::add<Viewport, StackItem, Drawable>(std::move(d));
		}

		template <class View, class ... Args>
		View addView(Args&& ... args) {
			return View::create(*this, m_proxy->m_windowData.eventDispatcher, m_proxy->m_windowData, m_proxy->m_windowData.settings, std::forward<Args>(args)...);
		}

	private:
		void onDispose() override;
	};

	class Sprite;
	class Text;
	class Shape;
	class FramedView;
	class SpriteMap;

	template <>
	struct ViewAuthorizations<Viewport> : public AuthorizedDrawableSet<Sprite, SpriteMap, Text, Shape, FramedView> {};

	namespace meta {
		template<>
		struct Log<Viewport> {
			static constexpr auto classname = "Viewport";
		};
	}
}

#endif
