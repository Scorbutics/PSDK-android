#ifndef CGSS_SPRITEMAP_H
#define CGSS_SPRITEMAP_H
#include "LiteCGSS/Graphics/Drawable.h"
#include "LiteCGSS/Graphics/Transformable.h"
#include "LiteCGSS/Common/Meta/metadata.h"
#include <SFML/Graphics/Sprite.hpp>

namespace cgss {
	class Texture;
	class SpriteMapItem;
	class View;
	class SpriteMap;

	class SpriteMapItem :
		public GraphicsStackItem {
		friend class SpriteMap;
	private:
		struct ActivableSprite {
			sf::Sprite sprite;
			bool active = false;
		};

		std::vector<ActivableSprite> m_sprites;
		unsigned long m_tileWidth = 1;
	public:
		SpriteMapItem(SpriteMap* owner);
		virtual ~SpriteMapItem() = default;

		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void defineMap(unsigned long tileWidth, unsigned long tileCount);
	};

	class SpriteMap :
		public Drawable<SpriteMap, SpriteMapItem> {
	public:
		SpriteMap() = default;
		SpriteMap(SpriteMap&&) = default;
		SpriteMap(const SpriteMap&) = delete;
		SpriteMap& operator=(const SpriteMap&) = delete;
		SpriteMap& operator=(SpriteMap&&) = default;

		void defineMap(unsigned long tileWidth, unsigned long tileCount);
		void setTile(std::size_t index, const sf::IntRect& tile, sf::Texture& texture);
		void setTileRect(std::size_t index, const sf::IntRect& tile);
		void move(float x, float y);
		void moveOrigin(float ox, float oy);
		void setTileScale(float scale);
		void reset();
	};

	namespace meta {
		template<>
		struct Log<SpriteMap> {
			static constexpr auto classname = "SpriteMap";
		};
	}
}

#endif
