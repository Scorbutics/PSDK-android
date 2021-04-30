#ifndef CGSS_LOADER_H
#define CGSS_LOADER_H

namespace cgss {
	template <class LoadedObject, class ... Args>
	class Loader {
	public:
		Loader() = default;
		virtual ~Loader() = default;
		virtual bool load(LoadedObject& object, Args&&... args) const = 0;
	};
}

#endif