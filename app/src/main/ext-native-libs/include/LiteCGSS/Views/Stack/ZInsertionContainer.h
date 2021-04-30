#ifndef CGSS_Z_INSERTION_CONTAINER_H
#define CGSS_Z_INSERTION_CONTAINER_H

#include <utility>
#include "ZBaseContainer.h"

namespace cgss {
	template <class ZIndexedElement>
	class ZInsertionContainer :
		public ZBaseContainer<ZIndexedElement> {
		using ZGet = ZGetter<ZIndexedElement>;
	public:
		using ZBaseContainer<ZIndexedElement>::ZBaseContainer;
		ZInsertionContainer& operator=(std::vector<ZIndexedElement>&& value) {
			this->m_stack = std::move(value);
			return *this;
		}

		void add(ZIndexedElement item) {
			const auto itemZ = ZGet::z(item);
			const auto position = this->findFirstGreater(itemZ);

			SKA_LOGC_STATIC(cgss::GetLoggerStack(), ska::LogLevel::Info, cgss::ZContainerLog) <<
				"Adding element to stack " << static_cast<void*>(this) << " with z-index = " <<
				itemZ.z << ", " << itemZ.index << " at position = " << position;

			if (position == this->m_stack.size()) {
				this->m_stack.push_back(std::move(item));
			} else {
				this->m_stack.insert(this->m_stack.begin() + position, std::move(item));
			}
		}

		void set(ZIndex& itemZ, long z) {
			auto nextZ = ZIndex{z, itemZ.index};
			const auto [originalIndex, finalIndex] = zSwap(itemZ, nextZ);
			SKA_LOGC_STATIC(cgss::GetLoggerStack(), ska::LogLevel::Debug, cgss::ZContainerLog) << "Zswapping to z = " << z;
			ZGet::setZ(this->m_stack[finalIndex], z);
		}

		template <class ZPredicate>
		bool removeOne(const ZIndex& itemZ, ZPredicate&& predicate) {
			return ZBaseContainer<ZIndexedElement>::removeOne(itemZ, std::forward<ZPredicate>(predicate));
		}

		void refresh() { }
	private:
		// What this function does :
		// RightCircleShift(1, 3, [A, B, C, D, E]) => [A, D, B, C, E]
		// RightCircleShift(0, 5, [A, B, C, D, E]) => [E, A, B, C, D]
		// Obviously we suppose there is not any out of bounds array access when calling this
		void rightCircleShift(std::size_t begin, std::size_t end) {
			auto last = std::move(this->m_stack[end]);
			for(auto index = end; index > begin; index--) {
				this->m_stack[index] = std::move(this->m_stack[index - 1]);
			}
			this->m_stack[begin] = std::move(last);
		}

		// The opposite of RightCircleShift
		void leftCircleShift(std::size_t begin, std::size_t end) {
			auto first = std::move(this->m_stack[begin]);
			for(auto index = begin; index < end; index++) {
				this->m_stack[index] = std::move(this->m_stack[index + 1]);
			}
			this->m_stack[end] = std::move(first);
		}

		std::pair<size_t, size_t> zSwap(const ZIndex& previous, const ZIndex& next) {
			std::size_t range[2] = { this->findFirstGreater(previous), this->findFirstGreater(next) };

			const auto diff = static_cast<long>(range[1] - range[0]);
			if (diff > 0) {
				range[1]--;
				leftCircleShift(range[0], range[1]);
			} else if (diff < 0) {
				rightCircleShift(range[1], range[0]);
			}

			SKA_LOGC_STATIC(cgss::GetLoggerStack(), ska::LogLevel::Debug, cgss::ZContainerLog)
			<< "Stack item zswaping, previous = " << previous.z << ", " << previous.index << " found at index = " << range[0]
			<< " next = " << next.z << ", " << next.index << " found at index = " << range[1];
			return std::make_pair(range[0], range[1]);
		}
	};
}

#endif