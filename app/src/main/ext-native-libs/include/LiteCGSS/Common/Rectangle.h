#ifndef CGSS_RECTANGLE_H
#define CGSS_RECTANGLE_H

#include <SFML/Graphics/Rect.hpp>
#include "Meta/metadata.h"
#include "LiteCGSS/Common/BondElement.h"

namespace cgss {
	class Rectangle :
		public BondElement<sf::IntRect> {
	public:
		Rectangle() = default;
		virtual ~Rectangle() = default;

		void setX(long x);
		void setY(long y);
		void setWidth(long width);
		void setHeight(long height);

	};

	namespace meta {
		template<>
		struct Log<Rectangle> {
			static constexpr auto classname = "Rectangle";
		};
	}

}

#endif