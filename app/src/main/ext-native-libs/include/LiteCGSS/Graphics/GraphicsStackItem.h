#ifndef CGSS_GRAPHICS_STACK_ITEM_H
#define CGSS_GRAPHICS_STACK_ITEM_H
#include <memory>
#include "GraphicItem.h"
#include "GraphicsStackItemPtr.h"
#include "LiteCGSS/Common/BondElement.h"
#include "LiteCGSS/Common/Bindable.h"
#include "LiteCGSS/Views/Stack/MainGraphicsStack.h"
#include "LiteCGSS/Views/Stack/ZGetter.h"
#include "ZIndex.h"

namespace cgss {

	class Rectangle;

	template <>
	struct ZGetter<GraphicsStackItemPtr>;

	class GraphicsStackItem :
		public Bindable<sf::IntRect>,
		protected GraphicItem {

		template <template <class> class ZAlgo>
		friend class GraphicsStack;

		friend struct ZGetter<GraphicsStackItemPtr>;
	private:
		MainGraphicsStack* m_originStack = nullptr;
		ZIndex m_priority;
		Rectangle* m_boundRectangle = nullptr;
		bool m_visible = true;

	public:
		static bool ZIndexSortPtr(const GraphicsStackItemPtr &lhs, const GraphicsStackItemPtr &rhs) { return lhs->getZ() < rhs->getZ(); }
		static bool ZIndexSort(const GraphicsStackItem &lhs, const GraphicsStackItem &rhs) { return lhs.getZ() < rhs.getZ(); }

		GraphicsStackItem() = default;
		GraphicsStackItem(const GraphicsStackItem&) = delete;
		GraphicsStackItem& operator=(const GraphicsStackItem&) = delete;
		GraphicsStackItem(GraphicsStackItem&&) = delete;
		GraphicsStackItem& operator=(GraphicsStackItem&&) = delete;

		virtual ~GraphicsStackItem();

		void updateFromValue(const sf::IntRect*) override {};
		virtual void setOpacity(std::uint8_t) {};

		void detach();

		const ZIndex& getZ() const { return m_priority; }
		void setZ(long z);

		void tryDraw(sf::RenderTarget& target) const;
		void tryDrawFast(sf::RenderTarget& target) const;

		void setVisible(bool value) { m_visible = value; };
		bool isVisible() const { return m_visible; };
	};

	template <>
	struct ZGetter<GraphicsStackItemPtr> {
		static const ZIndex& z(const GraphicsStackItemPtr& item) {
			return item->getZ();
		}

		static void setZ(GraphicsStackItemPtr& item, long z) {
			item->m_priority.z = z;
		}
	};

}
#endif