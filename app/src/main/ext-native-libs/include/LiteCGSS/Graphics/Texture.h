#ifndef CGSS_TEXTURE_H
#define CGSS_TEXTURE_H

#include <vector>
#include <memory>
#include <SFML/Graphics/Texture.hpp>
#include <SFML/Graphics/Image.hpp>
#include <SFML/Graphics/Color.hpp>
#include "LiteCGSS/Common/Meta/metadata.h"
#include "Serializers/TextureLoaderSaver.h"

namespace cgss {
	class Rectangle;
	class Shape;
	class DisplayWindow;
	class Image;

	class Texture {
	public:
		Texture();
		virtual ~Texture() = default;

		static Texture create(const DisplayWindow& window, const std::string& filename);
		static Texture create(const std::string& filename);
		static Texture create(sf::Texture texture);

		void update(const sf::Image* image = nullptr);
		void update(const Image& image);
		auto getSize() const { return m_texture->getSize(); }

		void blit(unsigned long x, unsigned long y, const Texture& srcTexture, const Rectangle& rect);
		void fillRect(long x, long y, unsigned int width, unsigned int height, sf::Color color);
		void clearRect(long x, long y, unsigned int width, unsigned int height);
		void setSmooth(bool smooth);

		Texture clone() const;

		bool load(const TextureLoader& loader);
		unsigned int write(TextureSaver& saver) const;

		bool isLoaded() const { return m_loaded; }

		bool create(int sw, int sh) { return m_texture->create(sw, sh); }

		std::weak_ptr<sf::Texture> weak() { return m_texture; }
		std::shared_ptr<sf::Texture> ref() { return m_texture; }
		sf::Texture& raw() { return *m_texture.get(); }
		const sf::Texture& raw() const { return *m_texture.get(); }

	private:
		bool hasImage() const { return m_image.getSize().x != 0; };

		bool m_loaded = false;
		std::shared_ptr<sf::Texture> m_texture;
		sf::Image m_image;
	};

	namespace meta {
		template<>
		struct Log<Texture> {
			static constexpr auto classname = "Texture";
		};
	}
}

#endif