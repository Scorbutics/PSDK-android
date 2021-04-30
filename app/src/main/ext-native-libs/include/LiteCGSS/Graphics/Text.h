#ifndef CGSS_TEXT_H
#define CGSS_TEXT_H
#include "LiteCGSS/Graphics/ScrollText.h"
#include "LiteCGSS/Graphics/Drawable.h"
#include "LiteCGSS/Graphics/Transformable.h"
#include "LiteCGSS/Common/Meta/metadata.h"

namespace cgss {
	class Text;
	class View;
	class TextItem : public GraphicsStackItem {
		friend class Text;
	public:
		TextItem(Text* owner);
		void draw(sf::RenderTarget& target) const override;
		void drawFast(sf::RenderTarget& target) const override;
		void updateAlign();
		void setOpacity(std::uint8_t opacity) override;
	private:
		ScrollText m_text;
		uint8_t m_align = 0;
		long m_width = 1;
		long m_height = 1;
		float m_wantedX = 0.f;
		float m_wantedY = 0.f;
	};

	class Text :
		public Drawable<Text, TextItem>,
		private Transformable {

	public:
		Text() = default;
		Text(Text&&) = default;
		Text(const Text&) = delete;
		Text& operator=(const Text&) = delete;
		Text& operator=(Text&&) = default;
		virtual ~Text() = default;

		std::uint8_t getOpacity() const;
		void setOpacity(std::uint8_t opacity);
		float getRealWidth() const;
		float getRealHeight() const;
		const sf::Color& getFillColor() const;
		void setFillColor(sf::Color color);
		const sf::Color& getOutlineColor() const;
		void setOutlineColor(sf::Color color);
		float getOutlineThickness() const;
		void setOutlineThickness(float outlineThickness);
		sf::Uint32 getStyle() const;
		void setStyle(sf::Uint32 style);
		void setAlign(uint8_t align);
		long getCharacterSize() const;
		void setCharacterSize(long characterSize);
		void setString(const sf::String& text);
		void setFont(const sf::Font& font);

		void setDrawShadow(bool drawshadow);
		bool getDrawShadow() const;
		void setNumCharToDraw(sf::Uint32 num);
		sf::Uint32 getNumCharToDraw() const;
		sf::Uint32 getTextWidth(const sf::String& string) const;
		void setSmooth(bool smooth);
		bool getSmooth() const;

		float getX() const;
		float getY() const;

		using Transformable::getOx;
		using Transformable::getOy;
		using Transformable::getScaleX;
		using Transformable::getScaleY;
		using Transformable::moveOrigin;
		using Transformable::scale;
		using Transformable::setInstance;

		void move(float x, float y);
		void resize(long width, long height);
		long getWidth() const;
		long getHeight() const;
	private:
		void setLineHeight(float height);
	};

	namespace meta {
		template<>
		struct Log<Text> {
			static constexpr auto classname = "Text";
		};
	}
}
#endif
