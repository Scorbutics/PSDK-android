#include <string>
#include <iostream>
#include <fstream>
#include <dirent.h>
#include <unistd.h>

#include <SFML/System/NativeActivity.hpp>

#include "jni-lib/jni_helper.h"
#include "ruby.h"

#ifndef NDEBUG
#include <android/log.h>
#include "logging.h"
#endif

int main(int argc, char* argv[]) {
	(void) argc;
	(void) argv;
	auto* activity = sf::getNativeActivity();

	const auto* externalWriteablePath = GetAppExternalFilesDir(activity);
#ifndef NDEBUG
	LoggingThreadRun("com.psdk.android", (std::string { externalWriteablePath } + "/last_stdout.log").c_str());
#endif
	const auto* internalWriteablePath = GetAppFilesDir(activity);
	if (chdir(externalWriteablePath) != 0) {
		std::cerr << "Cannot change current directory to '" << externalWriteablePath << "'" << std::endl;
		return 2;
	}

	const char* psdkLocation = GetAllocPSDKLocation(activity);
	setenv("PSDK_ANDROID_FOLDER_LOCATION", psdkLocation, 1);
#ifndef NDEBUG
	char mess[64];
	snprintf(mess, sizeof(mess), "Ruby thread started");
	__android_log_write(ANDROID_LOG_DEBUG, "com.psdk.android",  mess);
#endif
	const int rubyReturn = ExecRubyVM(internalWriteablePath);
#ifndef NDEBUG
	g_logging_thread_continue = 0;

	// Force "read" to end in logging thread
	std::cout << "Ruby thread ended : " << rubyReturn << std::endl;

	pthread_join(g_logging_thread, NULL);
#endif
	return rubyReturn;
}
