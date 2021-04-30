#ifndef CGSS_YUKI_GIF_H
#define CGSS_YUKI_GIF_H

#include <cstddef>
#include <memory>
#include <SFML/Graphics/Texture.hpp>

struct gif_animation;

namespace cgss {
	class YukiGif {
	public:
		static double FrameDelta;
		YukiGif();
		virtual ~YukiGif();

		bool load(const char* rawMemory, std::size_t size);
		void drawOn(sf::Texture& texture) const;

		bool update(sf::Texture& texture);

		unsigned int width() const;
		unsigned int height() const;
		unsigned long frame() const;
		unsigned long frameCount() const;
		void setFrame(unsigned long frame);

	private:
		std::unique_ptr<gif_animation> m_gif;
		unsigned long m_frame = 0;
		double m_counter = 0.0;
	};
}

#endif
