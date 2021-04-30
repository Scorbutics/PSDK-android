#ifndef CGSS_DISPLAY_WINDOW_DRAW_H
#define CGSS_DISPLAY_WINDOW_DRAW_H
#include <memory>

#include <SFML/Graphics/Sprite.hpp>
#include <SFML/Graphics/RenderTexture.hpp>

namespace cgss {
	struct DisplayWindowDrawData {
		sf::RenderStates* renderState = nullptr;
		std::unique_ptr<sf::RenderTexture> renderTexture = nullptr;
		unsigned long frameRate = 60;
		unsigned char brightness = 255;
		long screenWidth = 640;
		long screenHeight = 480;
		double scale = 1;
		bool smoothScreen = false;
	};
}
#endif
