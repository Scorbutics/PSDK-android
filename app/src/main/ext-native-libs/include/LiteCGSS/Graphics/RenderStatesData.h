#ifndef CGSS_RENDER_STATES_DATA_H
#define CGSS_RENDER_STATES_DATA_H

#include <memory>
#include <SFML/Graphics/RenderStates.hpp>
#include <SFML/Graphics/Shader.hpp>

namespace cgss {

	class RenderStatesData {
	private:
		sf::RenderStates m_renderStates;
		std::unique_ptr<sf::Shader> m_shader;
		bool m_shadersEnabled = false;

		void updateShader();
	public:
		RenderStatesData();
		RenderStatesData(RenderStatesData&&) = default;
		RenderStatesData(const RenderStatesData&) = delete;
		RenderStatesData& operator=(const RenderStatesData&) = delete;
		RenderStatesData& operator=(RenderStatesData&&) = default;
		virtual ~RenderStatesData() = default;

		void enableShader(bool enable);
		void warnIfShadersNotAvailable();
		bool enabled() const;

		void setBlendColorSrcFactor(sf::BlendMode::Factor colorSrcFactor);
		void setBlendColorDstFactor(sf::BlendMode::Factor colorDstFactor);
		void setBlendAlphaSrcFactor(sf::BlendMode::Factor alphaSrcFactor);
		void setBlendAlphaDstFactor(sf::BlendMode::Factor alphaDstFactor);

		void setBlendColorEquation(sf::BlendMode::Equation colorEquation);
		void setBlendAlphaEquation(sf::BlendMode::Equation alphaEquation);

		sf::BlendMode::Factor getBlendColorSrcFactor() const;
		sf::BlendMode::Factor getBlendColorDstFactor() const;
		sf::BlendMode::Factor getBlendAlphaSrcFactor() const;
		sf::BlendMode::Factor getBlendAlphaDstFactor() const;

		sf::BlendMode::Equation getBlendColorEquation() const;
		sf::BlendMode::Equation getBlendAlphaEquation() const;

		bool loadShaderFromMemory(const std::string& shader, sf::Shader::Type type);
		bool loadShaderFromMemory(const std::string& vertexShader, const std::string& fragmentShader);
		bool loadShaderFromMemory(const std::string &vertexShader, const std::string& geometryShader, const std::string& fragmentShader);

		void setShaderUniform(const std::string& name, float x);
		void setShaderUniform(const std::string& name, const sf::Glsl::Vec2& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Vec3& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Vec4& vector);
		void setShaderUniform(const std::string& name, int x);
		void setShaderUniform(const std::string& name, const sf::Glsl::Ivec2& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Ivec3& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Ivec4& vector);
		void setShaderUniform(const std::string& name, bool x);
		void setShaderUniform(const std::string& name, const sf::Glsl::Bvec2& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Bvec3& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Bvec4& vector);
		void setShaderUniform(const std::string& name, const sf::Glsl::Mat3& matrix);
		void setShaderUniform(const std::string& name, const sf::Glsl::Mat4& matrix);
		void setShaderUniform(const std::string& name, const sf::Texture& texture);
		void setShaderUniform(const std::string& name, sf::Shader::CurrentTextureType);

		void setShaderUniformArray(const std::string& name, const float* scalarArray, std::size_t length);

		const sf::RenderStates& getRenderStates() const;
		sf::RenderStates& getRenderStates();
	};
}

#endif