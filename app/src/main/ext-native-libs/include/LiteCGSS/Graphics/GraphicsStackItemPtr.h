#ifndef CGSS_GRAPHICS_STACK_ITEM_PTR_H
#define CGSS_GRAPHICS_STACK_ITEM_PTR_H
#include <memory>

namespace cgss {
	class GraphicsStackItem;
	using GraphicsStackItemPtr = std::shared_ptr<GraphicsStackItem>;
}
#endif
