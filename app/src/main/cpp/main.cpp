#include <iostream>

#include <SFML/Window/Keyboard.hpp>
#include <LiteCGSS/Views/DisplayWindow.h>
#include <LiteCGSS/Views/Viewport.h>
#include <LiteCGSS/Configuration/DisplayWindowSettings.h>
#include <LiteCGSS/Graphics/Sprite.h>
#include <LiteCGSS/Graphics/Texture.h>
#include <LiteCGSS/Graphics/Serializers/TextureSerializer.h>

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_INFO, "sfml-activity", __VA_ARGS__))

#include <ruby/ruby.h>
#include <dlfcn.h>

#include "sample_png.h"

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

static int pfd[2];
static pthread_t loggingThread;
static const char *LOG_TAG = "PSDK-android";

static void *loggingFunction(void*) {
    ssize_t readSize;
    char buf[2048];

    while((readSize = read(pfd[0], buf, sizeof buf - 1)) > 0) {
        if(buf[readSize - 1] == '\n') {
            --readSize;
        }

        buf[readSize] = 0;  // add null-terminator

        __android_log_write(ANDROID_LOG_DEBUG, LOG_TAG, buf); // Set any log level you want
    }

    return 0;
}

static int runLoggingThread() { // run this function to redirect your output to android log
    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* spawn the logging thread */
    if(pthread_create(&loggingThread, 0, loggingFunction, 0) == -1) {
        return -1;
    }

    pthread_detach(loggingThread);

    return 0;
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

static void OpenLib(const std::string& lib_path) {
    void* lib = dlopen(lib_path.c_str(), RTLD_NOW);
    if (lib == NULL) {
        std::cerr << "Could not dlopen(\"" << lib_path << "\"):" << dlerror() << std::endl;
        exit(122);
    } else {
        std::cerr << "Library " << lib_path << " loaded successfully" << std::endl;
    }
}

int main(int argc, char* argv[]) {
    (void)argc;
    (void)argv;
    //MemTest();
    //ViewTest();

    runLoggingThread();

    const auto appDir = GetAppDirectoryFromLib("/lib/arm64/");
    const auto lib64dir = appDir + "/lib/arm64/";
    static const char* LibDeps[] = { /*"libruby.so"*/ /*, "libcurses.so", "libpanel.so", "libform.so", "libmenu.so", "libhistory.so", "libgdbm.so", "libssl.so" */};
    for (const char* dep : LibDeps) {
        OpenLib(lib64dir + dep);
    }


    //dlclose(lib);

    ruby_sysinit(&argc, &argv);
    RUBY_INIT_STACK;
    ruby_init();
    ruby_init_loadpath();

    /* Permet de connaître le nom du script dans les messages d'erreur */
    //ruby_script("test_memory.rb");

    int error = 0;
    /* Charge le script dans l'interpréteur*/
    //rb_require("test_memory.rb");
    rb_eval_string("puts \"I Like chocolate\"");
    rb_load_file("test_memory.rb");
    //auto * n = rb_load_file("test_memory.rb");
    //ruby_run_node(n);

    LOGE("Error : %d", error);

    ruby_finalize();
    return 0;
}

