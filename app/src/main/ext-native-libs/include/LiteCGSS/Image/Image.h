#ifndef CGSS_IMAGE_H
#define CGSS_IMAGE_H

#include <cstddef>
#include <optional>
#include <SFML/Graphics/Image.hpp>
#include <SFML/Graphics/Color.hpp>
#include <SFML/Graphics/Rect.hpp>
#include "Serializers/ImageLoaderSaver.h"

namespace cgss {
	class Rectangle;
	class Texture;

	class Image {
		friend class Texture;
	public:
		Image() = default;
		~Image() = default;

		Image(const Image& image);
		Image& operator=(const Image& image);

		static Image create(const std::string& filename);
		static Image create(const char* rawMemory, std::size_t rawMemorySize);

		bool load(const ImageLoader& loader);
		unsigned int write(ImageSaver& saver) const;

		void blit(const sf::Image& source, unsigned int x, unsigned int y, const Rectangle& rect, bool applyAlpha = false);
		void stretchBlit(const sf::Image& imageSource, const Rectangle& destinationRect, const Rectangle& sourceRect, bool mixedAlpha = false);
		void fillRect(const sf::Color& color, unsigned int x, unsigned int y, unsigned int width, unsigned int height);

		unsigned int width() const;
		unsigned int height() const;
		sf::IntRect box() const;
		std::optional<sf::Color> getPixel(unsigned int x, unsigned int y) const;
		bool setPixel(unsigned int x, unsigned int y, const sf::Color& color);

		void createMaskFromColor(sf::Color color, unsigned char alpha);

		const sf::Image& raw() const { return m_image; }

	private:
		sf::Image m_image;
	};
}

#endif