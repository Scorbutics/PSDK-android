#include <SFML/Window/Keyboard.hpp>
#include <SFML/System/NativeActivity.hpp>
#include <LiteCGSS/Views/DisplayWindow.h>
#include <LiteCGSS/Views/Viewport.h>
#include <LiteCGSS/Configuration/DisplayWindowSettings.h>
#include <LiteCGSS/Graphics/Sprite.h>
#include <LiteCGSS/Graphics/Texture.h>
#include <LiteCGSS/Graphics/Serializers/TextureSerializer.h>

//#include <android/native_activity.h>
#include <android/log.h>

#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_INFO, "sfml-activity", __VA_ARGS__))

#include "prepare.h"
#include "sample_png.h"
#include "ruby.h"

void MemTest() {
    auto settings = cgss::DisplayWindowSettings{};
    settings.video.scale = 1;
    settings.visibleMouse = true;
    settings.video.width = 400;
    settings.video.height = 300;
    settings.title = "MemTest";
    auto window = cgss::DisplayWindow{ settings };

    auto viewport = window.addView<cgss::Viewport>();
    auto texture = cgss::Texture::create(window, "image.png");
    viewport.resize(200, 200);

    std::cout << "Before mem alloc" << std::endl;
    for (std::size_t i = 0; i < 1000000; i++) {
        auto sprite = cgss::Sprite::create(viewport);
        sprite.setTexture(texture);
        if (i%10000 == 0) {
            std::cout << (i / 10000.f) <<"%" << std::endl;
        }
    }
    std::cout << "Drawing..." << std::endl;
    window.draw();
    std::cout << "Before mem release" << std::endl;
    viewport.detach();
    std::cout << "After mem release" << std::endl;
}

static bool g_disposeView = false;
static sf::Vector2i g_windowPosition { 100, 100 };

static void HandleEvents(cgss::DisplayWindow& window, cgss::DisplayWindowSettings& settings) {
    sf::Event event;
    while (window.pollEvent(event)) {
        switch(event.type) {
            case sf::Event::EventType::Closed:
                window.stop();
                break;
            case sf::Event::EventType::KeyPressed:
                switch (event.key.code) {
                    case sf::Keyboard::F1:
                        if (window.screenHeight() > 15 && window.screenWidth() > 20) {
                            window.resizeScreen(window.screenWidth() - 20, window.screenHeight() - 15);
                            settings.video.height = window.screenHeight();
                            settings.video.width = window.screenWidth();
                        }
                        break;
                    case sf::Keyboard::F2:
                        window.resizeScreen(window.screenWidth() + 20, window.screenHeight() + 15);
                        settings.video.height = window.screenHeight();
                        settings.video.width = window.screenWidth();
                        break;
                    case sf::Keyboard::F3:
                        g_disposeView = true;
                        break;
                    case sf::Keyboard::F5:
                        window.move(window.getX() - 10, window.getY() - 10);
                        break;
                    case sf::Keyboard::F6:
                        window.move(window.getX() + 10, window.getY() + 10);
                        break;
                    case sf::Keyboard::Escape:
                        window.stop();
                        break;
                    case sf::Keyboard::Enter:
                        if (event.key.alt) {
                            settings.fullscreen = !settings.fullscreen;
                            window.reload(settings);
                        }
                        break;
                    default:
                        break;
                }
                break;
            case sf::Event::EventType::KeyReleased:
                switch (event.key.code) {
                    case sf::Keyboard::Escape:
                        g_disposeView = false;
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }
}

static void ViewTest() {
    auto settings = cgss::DisplayWindowSettings{};
    settings.video.scale = 1;
    settings.visibleMouse = true;
    settings.video.width = 400;
    settings.video.height = 300;
    settings.title = "Playground";
    auto window = cgss::DisplayWindow{ };

    const auto windowPos = sf::Vector2i {
            static_cast<int>(cgss::DisplayWindow::DesktopWidth() - settings.video.width) / 2,
            static_cast<int>(cgss::DisplayWindow::DesktopHeight() - settings.video.height) / 2
    };
    window.move(windowPos.x, windowPos.y);

    window.reload(std::move(settings), true);

    auto viewport = window.addView<cgss::Viewport>();
    auto serializer = cgss::TextureMemorySerializer{ {g_sample_png, g_sample_png_length} };
    auto texture = cgss::Texture{};
    if (!texture.load(serializer)) {
        throw std::runtime_error("Failed to load bitmap from memory.");
    }
    auto framedView = viewport.addView<cgss::FramedView>();

    auto fillTexture = cgss::Texture{};
    auto memorySerializer = cgss::TextureEmptySerializer{ 1000, 1000 };
    fillTexture.load(memorySerializer);
    fillTexture.fillRect(0, 0, 1000, 1000, sf::Color::White);

    //Move the INSIDE content (i.e. what is drawn), on SOURCE
    framedView.moveOrigin(60, 50);

    //Move the place where it's rendered on screen, on DESTINATION
    framedView.move(100, 100);

    //Resize portion of drawn content, on DESTINATION
    framedView.resize(100, 200);

    auto sprite3 = cgss::Sprite::create(viewport);
    auto sprite4 = cgss::Sprite::create(framedView);
    sprite3.setTexture(texture);
    sprite3.setColor({ 255, 120, 120 });
    //sprite3.move(20, 0);
    //sprite3.move(50, 100);

    sprite4.setTexture(fillTexture);
    //sprite4.setZ(0);

    auto sprite = cgss::Sprite::create(framedView);
    sprite.setTexture(texture);
    sprite.move(50, 100);
    sprite.setColor({ 120, 255, 120 });

    auto sprite2 = cgss::Sprite::create(window);
    sprite2.setTexture(texture);
    sprite2.move(200, 100);
    sprite2.setColor({128, 128, 255});

    viewport.move(50, 50);

    auto i = 0;
    {

        while (window.isOpen()) {
            window.draw();
            HandleEvents(window, settings);

            sprite.setAngle(sprite.getAngle() + 1.f);

            if (g_disposeView) {
                framedView.detach();
            }

            i++;
        }
    }
}

static std::string GetAppDirectoryFromLib(const std::string& base_lib_dir) {
    auto file = std::ifstream {"/proc/self/maps", std::ifstream::binary};
    auto line = std::string{};

    static const char data_app_dir[] = "/data/app/";

    while (std::getline(file, line)) {
        const auto start = line.find(data_app_dir);
        const auto end = line.find(base_lib_dir);
        if (start != std::string::npos && end != std::string::npos) {
            return line.substr(start, end - start);
        }
    }
    return "";
}
/*
static void OpenLib(const std::string& lib_path) {
    void* lib = dlopen(lib_path.c_str(), RTLD_NOW);
    if (lib == NULL) {
        std::cerr << "Could not dlopen(\"" << lib_path << "\"):" << dlerror() << std::endl;
        exit(122);
    } else {
        std::cerr << "Library " << lib_path << " loaded successfully" << std::endl;
    }
}

static void LoadAllLibs() {
    const auto appDir = GetAppDirectoryFromLib("/lib/arm64/");
    const auto lib64dir = appDir + "/lib/arm64/";
    static const char* LibDeps[] = { };//"libruby.so"*/ /*, "libcurses.so", "libpanel.so", "libform.so", "libmenu.so", "libhistory.so", "libgdbm.so", "libssl.so" };
    for (const char* dep : LibDeps) {
        OpenLib(lib64dir + dep);
    }
}
*/

/*
void ListFilesInFolder(const char* folder)
{
    DIR *d;
    struct dirent *dir;
    d = opendir(folder);
    if (d) {
        while ((dir = readdir(d)) != NULL) {
            printf("%s\n", dir->d_name);
        }
        closedir(d);
    } else {
        fprintf(stderr, "cannot open folder %s\n", folder);
    }
}*/

/*
static std::string GetAppName() {
    ANativeActivity* activity = sf::getNativeActivity();
    JNIEnv* env;
    activity->vm->AttachCurrentThread(&env, NULL);
    try {
        jclass android_content_Context = env->GetObjectClass(activity->clazz);
        jmethodID midGetPackageName = env->GetMethodID(android_content_Context, "getPackageName", "()Ljava/lang/String;");
        jstring packageName = (jstring) env->CallObjectMethod(activity->clazz, midGetPackageName);
        auto result = std::string{env->GetStringUTFChars(packageName, NULL)};
        activity->vm->DetachCurrentThread();
        return result;
    } catch (...) {
        activity->vm->DetachCurrentThread();
        return "";
    }
}*/

int main(int argc, char* argv[]) {
    (void) argc;
    (void) argv;
    //MemTest();
    //ViewTest();

    auto* activity = sf::getNativeActivity();
    //m_file = AAssetManager_open(activity->assetManager, filename.c_str(), AASSET_MODE_UNKNOWN);

    RunLoggingThread();
    const auto internalWriteablePath = std::string { GetAppFilesDir(activity) };
    const auto externalWriteablePath = std::string { GetAppExternalFilesDir(activity) };
    //const auto libRubyPath = internalWriteablePath + "/lib/ruby/";

    CopyAssetFile(activity->assetManager, "app_internal.zip", internalWriteablePath.c_str());
    CopyAssetFile(activity->assetManager, "app_data.zip", externalWriteablePath.c_str());

    //std::cout << internalWriteablePath << std::endl;
    //std::cout << externalWriteablePath << std::endl;

    //LoadAllLibs();

    /*std::ifstream starter_file { externalWriteablePath + "/starter.rb" };
    std::string line;
    while (std::getline(starter_file, line)) {
        std::cout << line << std::endl;
    }*/

    ExecRubyVM(internalWriteablePath.c_str(), (externalWriteablePath + "/starter.rb").c_str());

    return 0;
}

