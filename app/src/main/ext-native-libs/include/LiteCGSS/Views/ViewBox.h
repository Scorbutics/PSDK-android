#ifndef CGSS_INNER_VIEW_H
#define CGSS_INNER_VIEW_H

#include <SFML/Graphics/View.hpp>
#include <SFML/Graphics/RenderTarget.hpp>
#include "LiteCGSS/Events/Patterns/SubObserver.h"
#include "LiteCGSS/Events/WindowSizeChangeEvent.h"
#include "LiteCGSS/Events/MainEventDispatcher.h"
#include "LiteCGSS/Events/ViewportChangeEvent.h"

namespace cgss {

	struct DisplayWindowSettings;
	class ViewBox :
		private SubObserver<WindowSizeChangeEvent>,
		public Observable<ViewportChangeEvent> {

	public:
		ViewBox(ViewBox&&) noexcept = delete;
		ViewBox& operator=(ViewBox&&) noexcept = delete;
		ViewBox(const ViewBox&) = delete;
		ViewBox& operator=(const ViewBox&) = delete;

		virtual ~ViewBox() = default;

		void moveOrigin(long ox, long oy);
		void resize(long width, long height);

		ViewBox(MainEventDispatcher& ed, const DisplayWindowSettings& windowSettings);

		long getOx() const;
		long getOy() const;
		void setZoom(float zoom);
		void setAngle(float angle);
		float getAngle() const;
		float getZoom() const;

	protected:
		long getScreenWidth() const { return m_screenWidth; }
		long getScreenHeight() const { return m_screenHeight; }
		float getWidth() const;
		float getHeight() const;
		sf::View clone() const { return m_view; }
		void moveViewport(float x, float y);
		sf::IntRect getRectangleBox() const;

		void onRenderTarget(sf::RenderTarget& renderTarget, bool copy = false) const;

		virtual void onMove() {}
		virtual void onResize() {}

	private:
		bool onWindowSizeChange(WindowSizeChangeEvent& event);
		void updateCenter();

		sf::View m_view;
		mutable sf::View m_copy;

		bool m_triggerMove = true;
		bool m_triggerResize = true;

		float m_x = 0.f;
		float m_y = 0.f;
		long m_ox = 0;
		long m_oy = 0;
		long m_screenWidth = 640;
		long m_screenHeight = 320;
		float m_zoom = 1.f;
		float m_angle = 0.f;
	};
}
#endif
