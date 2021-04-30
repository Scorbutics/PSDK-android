#ifndef CGSS_NORMALIZE_NUMBERS_H
#define CGSS_NORMALIZE_NUMBERS_H

namespace cgss {
	inline long normalize_long(long value, long min, long max) {
		if (value < min) return min;
		if (value > max) return max;
		return value;
	}

	inline double normalize_double(double value, double min, double max) {
		if (value < min) return min;
		if (value > max) return max;
		return value;
	}
}

#endif