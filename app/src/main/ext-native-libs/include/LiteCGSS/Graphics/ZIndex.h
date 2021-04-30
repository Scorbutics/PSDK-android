#ifndef CGSS_ZINDEX_H
#define CGSS_ZINDEX_H

#include <sstream>
#include <vector>

namespace cgss {
	struct ZIndex {
		ZIndex() : z(0), index(0) {}
		ZIndex(long z) : z(z), index(0) {}
		ZIndex(long z, long index) : z(z), index(index) {}

		long z;
		long index;

		inline int sign() const {
			if (z != 0) {
				return z > 0 ? 1 : -1;
			}
			return index == 0 ? 0 : (index > 0 ? 1 : -1);
		}

		inline ZIndex operator+(const ZIndex& alter) const {
			return {z + alter.z, index + alter.index};
		}

		inline ZIndex operator-(const ZIndex& alter) const {
			return {z - alter.z, index - alter.index};
		}

		inline bool operator==(const ZIndex& alter) const {
			return z == alter.z && index == alter.index;
		}

		inline bool operator!=(const ZIndex& alter) const {
			return !operator==(alter);
		}

		inline bool operator>(const ZIndex& alter) const {
			if (z > alter.z) {
				return true;
			}
			return z == alter.z && index > alter.index;
		}

		inline bool operator<=(const ZIndex& alter) const {
			return !operator>(alter);
		}

		inline bool operator<(const ZIndex& alter) const {
			if (z < alter.z) {
				return true;
			}
			return z == alter.z && index < alter.index;
		}

		inline bool operator>=(const ZIndex& alter) const {
			return !operator<(alter);
		}

		friend std::ostream& operator<<(std::ostream& os, const ZIndex& element);
	};

	inline std::ostream& operator<<(std::ostream& os, const ZIndex& element) {
		os << "[" << element.z << ", " << element.index << "]";
		return os;
	}
}
#endif