#include <string>
#include <SFML/System/NativeActivity.hpp>

//#include <android/native_activity.h>
#include <android/log.h>
#include <dirent.h>
#include <unistd.h>

#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_INFO, "sfml-activity", __VA_ARGS__))

#include "prepare.h"
#include "sample_png.h"
#include "ruby.h"

int main(int argc, char* argv[]) {
    RunLoggingThread();
    (void) argc;
    (void) argv;

    auto* activity = sf::getNativeActivity();

    const auto internalWriteablePath = std::string { GetAppFilesDir(activity) };
    const auto externalWriteablePath = std::string { GetAppExternalFilesDir(activity) };

    CopyAssetFile(activity->assetManager, "app_internal.zip", internalWriteablePath.c_str());
    CopyAssetFile(activity->assetManager, "app_data.zip", externalWriteablePath.c_str());

    chdir(externalWriteablePath.c_str());
    ExecRubyVM(internalWriteablePath.c_str(), "./starter.rb");
    return 0;
}

