#ifndef CGSS_SERIALIZER_H
#define CGSS_SERIALIZER_H

#include <utility>
#include <string>

#include "Loader.h"
#include "Saver.h"

namespace cgss {

	template <class SerializeMethod, class TargetObject, class Loader, class Saver>
	class FileSerializer;

	template <class SerializeMethod, class TargetObject, class ... LoadArgs, class ... SaveArgs>
	class FileSerializer <SerializeMethod, TargetObject, Loader<TargetObject, LoadArgs ...>, Saver<TargetObject, SaveArgs ...>> :
		public Loader<TargetObject, LoadArgs ...>,
		public Saver<TargetObject, SaveArgs ...> {
	public:
		FileSerializer(std::string filename) :
			m_filename(std::move(filename)) {
		}
		bool load(TargetObject& object, LoadArgs&&... args) const override {
			return SerializeMethod::load(object, m_filename, std::forward<LoadArgs>(args)...);
		}
		unsigned int save(const TargetObject& object, SaveArgs&&... args) override {
			return SerializeMethod::save(object, m_filename, std::forward<SaveArgs>(args)...);
		}
	private:
		std::string m_filename;
	};


	template <class SerializeMethod, class TargetObject, class Loader, class Saver>
	class MemorySerializer;

	using MemorySerializerData = std::pair<unsigned char*, std::size_t>;

	template <class SerializeMethod, class TargetObject, class ... LoadArgs, class ... SaveArgs>
	class MemorySerializer <SerializeMethod, TargetObject, Loader<TargetObject, LoadArgs ...>, Saver<TargetObject, SaveArgs ...>> :
		public Loader<TargetObject, LoadArgs ...>,
		public Saver<TargetObject, SaveArgs ...> {
	public:
		MemorySerializer(MemorySerializerData rawData) :
			m_allocatedMemory(std::move(rawData)) {
		}

		MemorySerializer(const MemorySerializer&) = delete;
		MemorySerializer& operator=(const MemorySerializer&) = delete;
		MemorySerializer(MemorySerializer&&) = delete;
		MemorySerializer& operator=(MemorySerializer&&) = delete;

		virtual ~MemorySerializer() { cleanup(); }

		bool load(TargetObject& object, LoadArgs&&... args) const override {
			return SerializeMethod::load(object, m_allocatedMemory, std::forward<LoadArgs>(args)...);
		}

		unsigned int save(const TargetObject& object, SaveArgs&&... args) override {
			cleanup();
			m_allocatedMemory = std::move(SerializeMethod::save(object, std::forward<SaveArgs>(args)...));
			return m_allocatedMemory.first == nullptr ? 1 : 0;
		}

		template <class Function>
		void finalizeMemory(Function&& memoryUser) {
			std::forward<Function>(memoryUser)(m_allocatedMemory);
			cleanup();
		}

	private:
		void cleanup() {
			free(m_allocatedMemory.first);
			m_allocatedMemory = { nullptr, 0u };
		}
		MemorySerializerData m_allocatedMemory { nullptr, 0u };
	};

	template <class Serializer, class TargetObject, class Loader>
	class Empty2DSerializer;

	template <class Serializer, class TargetObject, class ... Args>
	class Empty2DSerializer<Serializer, TargetObject, Loader<TargetObject, Args ...>> :
		public Loader<TargetObject, Args ...> {
	public:
		Empty2DSerializer(unsigned int width, unsigned int height) :
			m_width(width),
			m_height(height) {
		}
		bool load(TargetObject& object, Args&&... args) const override {
			return Serializer::load(object, m_width, m_height, std::forward<Args>(args)...);
		}
	private:
		unsigned int m_width;
		unsigned int m_height;
	};
}

#endif