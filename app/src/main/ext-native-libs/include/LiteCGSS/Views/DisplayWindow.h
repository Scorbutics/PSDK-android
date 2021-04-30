#ifndef CGSS_DISPLAY_WINDOW_H
#define CGSS_DISPLAY_WINDOW_H

#include <memory>

#include <SFML/Graphics/Image.hpp>

#include "View.h"
#include "ViewAuthorizations.h"
#include "DisplayWindowDraw.h"
#include "DisplayWindowSnapshot.h"
#include "DisplayWindowUserData.h"
#include "FramedView.h"
#include "SnapshotCapturable.h"

#include "LiteCGSS/Events/MainEventDispatcher.h"
#include "LiteCGSS/Events/Patterns/SubObserver.h"
#include "LiteCGSS/Events/Patterns/Observable.h"
#include "LiteCGSS/Configuration/DisplayWindowSettings.h"

namespace cgss {

	struct DisplayWindowSettings;
	class DisplayWindow :
		public View,
		public SnapshotCapturable {

	public:
		static unsigned int DesktopWidth();
		static unsigned int DesktopHeight();

		DisplayWindow(const DisplayWindowSettings& settings);
		DisplayWindow(DisplayWindow&&) = default;
		DisplayWindow& operator=(DisplayWindow&&) = delete;
		DisplayWindow();
		virtual ~DisplayWindow();

		unsigned char brightness() const { return m_draw.brightness; }
		long screenWidth() const { return m_draw.screenWidth; }
		long screenHeight() const { return m_draw.screenHeight; }
		bool smoothScreen() const { return m_draw.smoothScreen; }
		double scale() const { return m_draw.scale; }
		auto frameRate() const { return m_draw.frameRate; }
		int getX() const;
		int getY() const;

		void stop();
		bool isOpen() const;
		void draw();
		bool pollEvent(sf::Event& event);

		std::unique_ptr<sf::Texture> takeSnapshot() const override;
		void freeze();
		void transition(long time, Texture* texture = nullptr);

		void setBrightness(unsigned char brightness) { m_draw.brightness = brightness; }
		void setShader(sf::RenderStates* shader);
		void setIcon(const sf::Image& image);
		void move(int x, int y);

		/* Settings related stuff */
		void reload(DisplayWindowSettings settings, bool force = false);
		const DisplayWindowSettings& getSettings() const;
		const DisplayWindowContextSettings& getContextSettings() const;
		void resizeScreen(unsigned int width, unsigned int height);
		void setVsync(bool enable);
		void setTitle(const char* title);
		void setFrameRate(unsigned int frameRate);
		void setScale(double scale);

		template <class View, class ... Args>
		View addView(Args&& ... args) {
			return View::create(*this, m_eventDispatcher, buildUserData(), m_settings, std::forward<Args>(args)...);
		}

		template <class Event>
		void record(Observer<Event>& observer) {
			m_eventDispatcher.template Observable<Event>::addObserver(observer);
		}

		template <class Event>
		void erase(Observer<Event>& observer) {
			m_eventDispatcher.template Observable<Event>::removeObserver(observer);
		}

		MainEventDispatcher& eventDispatcher() { return m_eventDispatcher; }

	private:
		DisplayWindowUserData buildUserData() {
			return { m_eventDispatcher, m_settings, m_userRenderSprite, m_userRenderTexture };
		}
		void onSizeChanged();
		void notifyWindowSizeChange();

		void initUserRender();
		void drawBrightness();
		sf::RenderTarget& configureAndGetRenderTarget(sf::View& defview);
		void postProcessing();

		MainEventDispatcher m_eventDispatcher {};
		DisplayWindowSettings m_settings {};
		sf::RenderWindow m_gameWindow {};
		DisplayWindowSnapshot m_snapshot;
		DisplayWindowDrawData m_draw;

		/* The user render sprite and texture belong to the window and are only used by children element on the window.
			As of today, only viewports use this. The use of render sprite and texture is to be used as the target view of
			children viewports (when render states, so shaders, are enabled).
		*/
		std::unique_ptr<sf::Sprite> m_userRenderSprite = std::make_unique<sf::Sprite>();
		std::unique_ptr<sf::RenderTexture> m_userRenderTexture = std::make_unique<sf::RenderTexture>();
	};

	class Sprite;
	class Text;
	class Viewport;
	class Shape;

	template <>
	struct ViewAuthorizations<DisplayWindow> : public AuthorizedDrawableSet<Sprite, Text, Shape, Viewport, FramedView> {};
}

#endif