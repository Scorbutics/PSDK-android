#include <assert.h>

#include <SFML/System/NativeActivity.hpp>

#include "jni_psdk.h"

int main(int argc, char* argv[]) {
	(void) argc;
	(void) argv;
	auto* activity = sf::getNativeActivity();
	assert(activity != NULL);
    return StartGameFromNativeActivity(activity);
}
