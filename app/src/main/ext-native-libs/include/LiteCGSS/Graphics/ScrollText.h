/*

LiteCGSS integration Note :
THIS SOURCE FILE IS AN ALTERED VERSION OF sf::Text FOUND IN Text.hpp OF SFML GRAPHICS MODULE
IF YOU WANT TO CHECK THE ORIGINAL FILE, LOOK AT SFML IMPLEMENTATION.

*/

/*
SFML (Simple and Fast Multimedia Library) - Copyright (c) Laurent Gomila

This software is provided 'as-is', without any express or implied warranty.
In no event will the authors be held liable for any damages arising from
the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not claim
   that you wrote the original software. If you use this software in a product,
   an acknowledgment in the product documentation would be appreciated but is
   not required.

2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.

3. This notice may not be removed or altered from any source distribution.
*/

#ifndef CGSS_SCROLL_TEXT_H
#define CGSS_SCROLL_TEXT_H

#include <SFML/Graphics/Export.hpp>
#include <SFML/Graphics/Drawable.hpp>
#include <SFML/Graphics/Transformable.hpp>
#include <SFML/Graphics/Font.hpp>
#include <SFML/Graphics/Rect.hpp>
#include <SFML/Graphics/VertexArray.hpp>
#include <SFML/System/String.hpp>
#include <string>
#include <limits>
#include <vector>

namespace cgss {

	class ScrollText :
		public sf::Drawable,
		public sf::Transformable {
	public:

		enum Style {
			Regular = 0,
			Bold = 1 << 0,
			Italic = 1 << 1,
			Underlined = 1 << 2,
			StrikeThrough = 1 << 3
		};

		ScrollText();
		ScrollText(const sf::String& string, const sf::Font& font, unsigned int characterSize = 30);

		void setString(const sf::String& string);
		void setFont(const sf::Font& font);
		void setCharacterSize(unsigned int size);
		void setLineSpacing(float spacingFactor);
		void setLetterSpacing(float spacingFactor);
		void setStyle(sf::Uint32 style);
		void setFillColor(const sf::Color& color);
		void setOutlineColor(const sf::Color& color);
		void setOutlineThickness(float thickness);
		const sf::String& getString() const;
		const sf::Font* getFont() const;
		unsigned int getCharacterSize() const;
		float getLetterSpacing() const;
		float getLineSpacing() const;
		sf::Uint32 getStyle() const;
		const sf::Color& getFillColor() const;
		const sf::Color& getOutlineColor() const;
		float getOutlineThickness() const;
		sf::Vector2f findCharacterPos(std::size_t index) const;
		sf::FloatRect getLocalBounds() const;
		sf::FloatRect getGlobalBounds() const;
		void setDrawShadow(bool drawshadow);
		bool getDrawShadow() const;
		void setNumCharToDraw(sf::Uint32 num);
		sf::Uint32 getNumCharToDraw() const;
		void setLineHeight(float height);
		sf::Uint32 getTextWidth(const sf::String& string) const;
		void setSmooth(bool smooth);
		bool getSmooth() const;

	private:
		virtual void draw(sf::RenderTarget& target, sf::RenderStates states) const;
		void ensureGeometryUpdate() const;

		sf::String m_string;
		const sf::Font* m_font = NULL;
		unsigned int m_characterSize;
		float m_letterSpacingFactor = 1.f;
		float m_lineSpacingFactor = 1.f;
		sf::Uint32 m_style;
		sf::Color m_fillColor;
		sf::Color m_outlineColor;
		float m_outlineThickness = 0.f;
		mutable sf::VertexArray m_vertices;
		mutable sf::VertexArray m_outlineVertices;
		mutable sf::FloatRect m_bounds;
		mutable bool m_geometryNeedUpdate;
		mutable const sf::Texture* m_fontTextureRef = nullptr;
		bool m_drawShadow = true;
		sf::Uint32 m_numberCharsToDraw = std::numeric_limits<std::uint32_t>::max();
		float m_lineHeight = 16.0f;
		bool m_smooth = false;
	};
}


#endif
