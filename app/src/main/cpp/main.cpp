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

static void CopyLibsToInternal(const std::string& internalWriteablePath, const std::string& psdkFolder) {
	static const char* PSDK_LIBS[] = { "LiteRGSS.so", "SFMLAudio.so", NULL };
	for (size_t i = 0; PSDK_LIBS[i] != NULL; i++) {
		auto sourceLibFile = std::ifstream{ psdkFolder + std::string{PSDK_LIBS[i]} };
		if (!sourceLibFile) {
			std::cout << "No lib named '" << PSDK_LIBS[i] << "' found in PSDK folder, continuing" << std::endl;
			continue;
		}
		auto destinationLibFile = std::ofstream { internalWriteablePath + "/" + std::string{PSDK_LIBS[i]}};
		if (!destinationLibFile) {
			std::cerr << "Error while trying to copy lib '" << PSDK_LIBS[i] << "'to internal memory, continuing" << std::endl;
			continue;
		}
		destinationLibFile << sourceLibFile.rdbuf();
	}
}

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

	const char* perms[] = { "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", NULL };
	if (!request_android_permissions(activity, perms)) {
		std::cerr << "Cannot require app permissions" << std::endl;
	}

  setenv("PSDK_ANDROID_FOLDER_LOCATION", (std::string {GetExternalStorageDir(activity)} + "/PSDK/").c_str(), 1);
	CopyLibsToInternal(internalWriteablePath, std::string {GetExternalStorageDir(activity)} + "/PSDK/");
  return ExecRubyVM(internalWriteablePath.c_str(), "./starter.rb");
}
