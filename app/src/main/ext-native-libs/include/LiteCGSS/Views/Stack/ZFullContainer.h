#ifndef CGSS_Z_FULL_CONTAINER_H
#define CGSS_Z_FULL_CONTAINER_H

#include <algorithm>
#include "ZBaseContainer.h"

namespace cgss {

	template <class ZIndexedElement>
	class ZFullContainer :
		public ZBaseContainer<ZIndexedElement> {
		using ZGet = ZGetter<ZIndexedElement>;
	public:
		using ZBaseContainer<ZIndexedElement>::ZBaseContainer;
		ZFullContainer& operator=(std::vector<ZIndexedElement>&& value) {
			this->m_stack = std::move(value);
			return *this;
		}

		void add(ZIndexedElement item) {
			this->m_stack.push_back(std::move(item));
			setZDirty();
		}

		void set(ZIndex& index, long z) {
			setZDirty();
			index.z = z;
		}

		template<class ZPredicate>
		bool removeOne(const ZIndex& itemZ, ZPredicate&& predicate = {}) {
			sortZ();
			return cgss::ZBaseContainer<ZIndexedElement>::removeOne(itemZ, std::forward<ZPredicate>(predicate));
		}

		void refresh() {
			sortZ();
		}

	private:
		static bool ZIndexSort(const ZIndexedElement &lhs, const ZIndexedElement &rhs) { return ZGet::z(lhs) < ZGet::z(rhs); }

		void sortZ() {
			if (m_zDirty) {
				std::sort(this->m_stack.begin(), this->m_stack.end(), ZIndexSort);
				m_zDirty = false;
			}
		}

		void setZDirty() {
			m_zDirty = true;
		}

		bool m_zDirty = false;
	};
}

#endif