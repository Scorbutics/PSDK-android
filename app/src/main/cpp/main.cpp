#include <string>
#include <iostream>
#include <fstream>
#include <dirent.h>
#include <unistd.h>

#include <SFML/System/NativeActivity.hpp>

#include "jni-lib/jni_helper.h"
#include "ruby.h"

#ifndef NDEBUG
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
  return ExecRubyVM(internalWriteablePath, "./starter.rb");
}
