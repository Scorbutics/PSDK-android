#ifndef CGSS_IMAGE_SERIALIZER_H
#define CGSS_IMAGE_SERIALIZER_H

#include <SFML/Graphics/Image.hpp>
#include "LiteCGSS/Common/Serializer.h"
#include "ImageLoaderSaver.h"

namespace cgss {
	struct ImageFileSerializeMethod {
		static bool load(sf::Image& image, const std::string& filename);
		static unsigned int save(const sf::Image& image, const std::string& filename);
	};

	using ImageFileSerializer = FileSerializer<ImageFileSerializeMethod, sf::Image, ImageLoader, ImageSaver>;

	struct ImageMemorySerializeMethod {
		static bool load(sf::Image& image, const MemorySerializerData& rawData);
		static MemorySerializerData save(const sf::Image& image);
	};

	using ImageMemorySerializer = MemorySerializer<ImageMemorySerializeMethod, sf::Image, ImageLoader, ImageSaver>;

	struct ImageEmptySerializeMethod {
		static bool load(sf::Image& image, unsigned int width, unsigned int height);
	};

	using ImageEmptySerializer = Empty2DSerializer<ImageEmptySerializeMethod, sf::Image, ImageLoader>;
}
#endif