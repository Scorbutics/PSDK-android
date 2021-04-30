#ifndef CGSS_SAVER_H
#define CGSS_SAVER_H

namespace cgss {
	template <class SavedObject, class ... Args>
	class Saver {
	public:
		Saver() = default;
		virtual ~Saver() = default;
		virtual unsigned int save(const SavedObject& object, Args&&... args) = 0;
	};
}

#endif