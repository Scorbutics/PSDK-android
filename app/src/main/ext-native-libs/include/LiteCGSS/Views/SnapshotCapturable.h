#ifndef CGSS_SNAPSHOT_CAPTURABLE_H
#define CGSS_SNAPSHOT_CAPTURABLE_H

#include <memory>
#include <SFML/Graphics/Texture.hpp>

namespace cgss {
	class SnapshotCapturable {
	public:
		SnapshotCapturable() = default;
		virtual ~SnapshotCapturable() = default;

		virtual std::unique_ptr<sf::Texture> takeSnapshot() const = 0;
	};
}

#endif