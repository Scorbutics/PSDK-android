#ifndef CGSS_RENDER_STATES_H
#define CGSS_RENDER_STATES_H

#include <memory>
#include <string>

#include "RenderStatesData.h"
#include "LiteCGSS/Common/BondElement.h"
#include "LiteCGSS/Common/Meta/metadata.h"

namespace cgss {

	class RenderStates :
		public BondElement<RenderStatesData> {
	private:
		void updateShader();
	public:
		RenderStates() = default;
		RenderStates(RenderStates&&) = default;
		RenderStates(const RenderStates&) = delete;
		RenderStates& operator=(const RenderStates&) = delete;
		RenderStates& operator=(RenderStates&&) = default;
		virtual ~RenderStates() = default;

		static bool areShadersEnabled();
		static bool areGeometryShadersEnabled();

		RenderStatesData& data() { return value(); }
	};

	namespace meta {
		template<>
		struct Log<RenderStates> {
			static constexpr auto classname = "RenderStates";
		};
	}

}

#endif