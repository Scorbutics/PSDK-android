#ifndef CGSS_VIEWPORT_CHANGE_EVENT_H
#define CGSS_VIEWPORT_CHANGE_EVENT_H

#include <SFML/Graphics/Rect.hpp>

namespace cgss {
	struct ViewportChangeEvent {
		sf::IntRect viewportBox;
	};
}

#endif