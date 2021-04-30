#ifndef CGSS_MAIN_GRAPHICS_STACK_H
#define CGSS_MAIN_GRAPHICS_STACK_H

#include "ZFullContainer.h"
#include "LiteCGSS/Graphics/GraphicsStackItemPtr.h"

namespace cgss {
	template <template <class> class ZAlgo>
	class GraphicsStack;
	using MainGraphicsStack = GraphicsStack<ZFullContainer>;
}
#endif