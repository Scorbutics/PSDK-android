#ifndef CGSS_SHAPE_H
#define CGSS_SHAPE_H
#include <memory>
#include "LiteCGSS/Graphics/Drawable.h"
#include "LiteCGSS/Graphics/Transformable.h"
#include "LiteCGSS/Common/Meta/metadata.h"

namespace cgss {
	class Texture;
	class View;
	class Shape;

	enum class ShapeType {
		Rectangle,
		Circle,
		Convex
	};

	using ShapeData = std::pair<ShapeType, std::unique_ptr<sf::Shape>>;

	class ShapeItem :
		public GraphicsStackItem {
		friend class Shape;
	private:
		std::unique_ptr<sf::Shape> m_shape;
		std::shared_ptr<sf::Texture> m_linkedTexture = nullptr;
		ShapeType m_type = ShapeType::Rectangle;
		sf::RectangleShape m_defaultShape;

		sf::RenderStates m_renderStates = sf::RenderStates::Default;
	public:
		ShapeItem(Shape* owner, ShapeData shape);
		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void updateFromValue(const sf::IntRect* rectangle) override;
	};

	class Shape :
		public Drawable<Shape, ShapeItem>,
		public Transformable {
	public:
		Shape() = default;
		Shape(Shape&&) = default;
		Shape(const Shape&) = delete;
		Shape& operator=(const Shape&) = delete;
		Shape& operator=(Shape&&) = default;
		virtual ~Shape() = default;

		void setRenderState(sf::RenderStates states);
		void setTexture(Texture* texture, bool resetRect = false);

		sf::IntRect getRectangle() const;
		void setRectangle(sf::IntRect rectangle);

		std::size_t getPointCount() const;
		void setPointCount(std::size_t pointCount) const;

		float getRadius() const;
		void setRadius(float radius);

		sf::Vector2f getPoint(const std::size_t index) const;
		void setPoint(const std::size_t index, float x, float y);

		sf::Color getFillColor() const;
		void setFillColor(sf::Color color);

		sf::Color getOutlineColor() const;
		void setOutlineColor(sf::Color color);

		float getOutlineThickness() const;
		void setOutlineThickness(float thickness) const;

		long getWidth() const;
		void setWidth(long width);

		long getHeight() const;
		void setHeight(long height);
	};

	namespace meta {
		template<>
		struct Log<Shape> {
			static constexpr auto classname = "Shape";
		};
	}
}
#endif