#ifndef CGSS_SPRITE_H
#define CGSS_SPRITE_H

#include <memory>
#include "LiteCGSS/Graphics/Drawable.h"
#include "LiteCGSS/Graphics/Transformable.h"
#include "LiteCGSS/Graphics/ShaderRenderable.h"
#include "LiteCGSS/Common/Meta/metadata.h"

namespace cgss {
	class Texture;
	class Sprite;
	class View;
	class RenderStates;

	class SpriteItem :
		public GraphicsStackItem,
		public ShaderRenderable {
		friend class Sprite;
	private:
		sf::Sprite m_sprite;
		std::shared_ptr<sf::Texture> m_linkedTexture = nullptr;
		bool m_mirror = false;
		float m_wantedX = 0;
		float m_wantedY = 0;
	public:
		SpriteItem(Sprite* owner);
		virtual ~SpriteItem() = default;
		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void updateFromValue(const sf::IntRect* rectangle) override;
		void updateFromValue(const RenderStatesData* value) override;
		void setOpacity(std::uint8_t opacity) override;
	};

	class Sprite :
		public Drawable<Sprite, SpriteItem>,
		private Transformable {
	private:
		void horizontalFlip();

	public:
		Sprite() = default;
		Sprite(Sprite&&) = default;
		Sprite(const Sprite&) = delete;
		Sprite& operator=(const Sprite&) = delete;
		Sprite& operator=(Sprite&&) = default;
		virtual ~Sprite() = default;

		float getX() const;
		float getY() const;
		void move(float x, float y);

		using Transformable::getOx;
		using Transformable::getOy;
		using Transformable::getScaleX;
		using Transformable::getScaleY;
		using Transformable::moveOrigin;
		using Transformable::scale;
		using Transformable::setInstance;
		using Transformable::getAngle;
		using Transformable::setAngle;

		sf::Color getColor() const;
		void setColor(sf::Color color);

		void bindRenderStates(RenderStates* renderStates);
		void setTexture(Texture& texture, bool resetRect = false);
		void setTextureRect(sf::IntRect rectangle);
		const sf::IntRect& getTextureRect() const;
		void setMirror(bool mirror);
	};

	namespace meta {
		template<>
		struct Log<Sprite> {
			static constexpr auto classname = "Sprite";
		};
	}
}
#endif
