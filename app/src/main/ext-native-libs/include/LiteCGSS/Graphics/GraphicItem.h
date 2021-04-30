#ifndef CGSS_GRAPHIC_ITEM_H
#define CGSS_GRAPHIC_ITEM_H

#include <SFML/Graphics.hpp>

namespace cgss {
	class GraphicItem {
	public:
		GraphicItem() = default;
		virtual ~GraphicItem() = default;

		virtual void draw(sf::RenderTarget& target) const = 0;
		virtual void drawFast(sf::RenderTarget& target) const = 0;
	};
}
#endif
