#ifndef CGSS_IMAGE_LOADER_SAVER_H
#define CGSS_IMAGE_LOADER_SAVER_H

#include <SFML/Graphics/Image.hpp>
#include "LiteCGSS/Common/Loader.h"
#include "LiteCGSS/Common/Saver.h"

namespace cgss {
	using ImageLoader = Loader<sf::Image>;
	using ImageSaver = Saver<sf::Image>;
}

#endif