#ifndef CGSS_SHAPE_LOADER_H
#define CGSS_SHAPE_LOADER_H

#include "Graphics/Shape.h"

namespace cgss {

	class ShapeLoader {
	public:
		using ShapeData = std::pair<ShapeType, std::unique_ptr<sf::Shape>>;

		static ShapeData buildCircle(float radius, unsigned long numPoints) {
			return build<sf::CircleShape>(ShapeType::Circle, radius, numPoints);
		}

		static ShapeData buildConvex(unsigned long numPoints) {
			return build<sf::ConvexShape>(ShapeType::Convex, numPoints);
		}

		static ShapeData buildRectangle(float radNumPoints, float numPoints) {
			return build<sf::RectangleShape>(ShapeType::Rectangle, sf::Vector2f(radNumPoints, numPoints) );
		}

	private:
		template <class ShapeT, class ... Args>
		static ShapeData build(ShapeType shapeType, Args&& ... args) {
			return std::make_pair(shapeType, std::unique_ptr<sf::Shape>( new ShapeT(std::forward<Args>(args)...) ));
		}
	};

}
#endif