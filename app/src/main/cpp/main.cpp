#include <string>
#include <iostream>
#include <fstream>
#include <dirent.h>
#include <unistd.h>

#include <SFML/System/NativeActivity.hpp>

#include "jni_helper.h"
#include "ruby.h"
#ifndef NDEBUG
#include "logging.h"
#endif

int main(int argc, char* argv[]) {
#ifndef NDEBUG
	LoggingThreadRun("com.psdk.android");
#endif
  (void) argc;
  (void) argv;

  auto* activity = sf::getNativeActivity();

  const auto internalWriteablePath = std::string { GetAppFilesDir(activity) };
  const auto externalWriteablePath = std::string { GetAppExternalFilesDir(activity) };

  const auto assetsDone = internalWriteablePath + "/.assets.done";

	if (!std::ifstream { assetsDone }) {
		const int copyInternalResult = CopyAssetFile(activity->assetManager, "app_internal.zip", internalWriteablePath.c_str());
		if (copyInternalResult != 0) {
			std::cerr << "Cannot extract " << internalWriteablePath << "/app_internal.zip (error " << copyInternalResult << ")" << std::endl;
			return 1;
		}
		const int copyDataResult = CopyAssetFile(activity->assetManager, "app_data.zip", externalWriteablePath.c_str());
		if (copyDataResult != 0) {
			std::cerr << "Cannot extract " << externalWriteablePath << "/app_data.zip (error " << copyDataResult << ")" << std::endl;
			return 1;
		}

		if (!std::ofstream { assetsDone }) {
			std::cerr << "Unable to create file '" << assetsDone << "' to indicate that assets have been unzipped successfully" << std::endl;
		}
	}
  if (chdir(externalWriteablePath.c_str()) != 0) {
		std::cerr << "Cannot change current directory to '" << externalWriteablePath << "'" << std::endl;
		return 2;
  }

  return ExecRubyVM(internalWriteablePath.c_str(), "./starter.rb");
}

