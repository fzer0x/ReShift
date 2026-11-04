#include <jni.h>
#include <string>
#include <unordered_map>
#include <mutex>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "ReShiftNativeSpoofer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct SpoofConfig {
    std::string brand;
    std::string manufacturer;
    std::string model;
    std::string product;
    std::string device;
    std::string board;
    std::string hardware;
    std::string fingerprint;
    std::string serial;
    std::string releaseVersion;
    std::string sdkInt;
    std::string securityPatch;
    bool active = false;
};

static SpoofConfig g_config;
static std::mutex g_config_mutex;

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static void (*orig_system_property_read_callback)(const prop_info *pi,
                                                   void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
                                                   void *cookie) = nullptr;

static std::string to_lower(const std::string &str) {
    std::string lower = str;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower;
}

static const char* get_spoofed_value(const char* name) {
    if (!name) return nullptr;
    std::lock_guard<std::mutex> lock(g_config_mutex);
    if (!g_config.active) return nullptr;

    std::string k = to_lower(name);
    bool isQcom = to_lower(g_config.hardware).find("qcom") != std::string::npos ||
                  to_lower(g_config.board).find("kalama") != std::string::npos ||
                  to_lower(g_config.board).find("shennong") != std::string::npos;

    // Model & Marketname
    if (k.rfind(".model") != std::string::npos || k == "ro.product.model" || k == "ro.product.marketname") {
        return g_config.model.c_str();
    }
    // Brand
    if (k.rfind(".brand") != std::string::npos || k == "ro.product.brand") {
        return g_config.brand.c_str();
    }
    // Manufacturer
    if (k.rfind(".manufacturer") != std::string::npos || k == "ro.product.manufacturer") {
        return g_config.manufacturer.c_str();
    }
    // Device
    if (k.rfind(".device") != std::string::npos || k == "ro.product.device") {
        return g_config.device.c_str();
    }
    // Product / Name
    if ((k.rfind(".name") != std::string::npos && k.find("ro.product") == 0) || k == "ro.product.name") {
        return g_config.product.c_str();
    }
    // Board & Platform
    if (k == "ro.product.board" || k == "ro.board.platform" || k == "ro.mediatek.platform") {
        return g_config.board.c_str();
    }
    // Hardware & Chipset
    if (k == "ro.hardware" || k == "ro.boot.hardware" || k == "ro.chipname") {
        return g_config.hardware.c_str();
    }
    // Fingerprint
    if (k.rfind(".fingerprint") != std::string::npos || k == "ro.build.fingerprint") {
        return g_config.fingerprint.c_str();
    }
    // Serial
    if (k == "ro.serialno" || k == "ro.boot.serialno") {
        return g_config.serial.c_str();
    }
    // Release Version
    if (k.rfind(".version.release") != std::string::npos || k == "ro.build.version.release") {
        return g_config.releaseVersion.c_str();
    }
    // Security Patch
    if (k.rfind(".version.security_patch") != std::string::npos || k == "ro.build.version.security_patch") {
        return g_config.securityPatch.c_str();
    }
    // SoC
    if (k == "ro.soc.manufacturer") {
        return isQcom ? "Qualcomm" : g_config.manufacturer.c_str();
    }
    if (k == "ro.soc.model") {
        return g_config.board.c_str();
    }

    return nullptr;
}

int hooked_system_property_get(const char *name, char *value) {
    const char* spoofed = get_spoofed_value(name);
    if (spoofed != nullptr && value != nullptr) {
        strncpy(value, spoofed, PROP_VALUE_MAX - 1);
        value[PROP_VALUE_MAX - 1] = '\0';
        return static_cast<int>(strlen(value));
    }
    if (orig_system_property_get != nullptr) {
        return orig_system_property_get(name, value);
    }
    return 0;
}

struct CallbackWrapper {
    void (*orig_cb)(void *cookie, const char *name, const char *value, uint32_t serial);
    void *orig_cookie;
};

static void intercept_prop_cb(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *wrapper = static_cast<CallbackWrapper*>(cookie);
    if (!wrapper || !wrapper->orig_cb) return;

    const char* spoofed = get_spoofed_value(name);
    if (spoofed != nullptr) {
        wrapper->orig_cb(wrapper->orig_cookie, name, spoofed, serial);
    } else {
        wrapper->orig_cb(wrapper->orig_cookie, name, value, serial);
    }
}

void hooked_system_property_read_callback(const prop_info *pi,
                                           void (*callback)(void *cookie, const char *name, const char *value, uint32_t serial),
                                           void *cookie) {
    if (orig_system_property_read_callback != nullptr) {
        CallbackWrapper wrapper{callback, cookie};
        orig_system_property_read_callback(pi, intercept_prop_cb, &wrapper);
    }
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_ox_fzer0x_snakeloader_xposed_FingerprintSpooferHook_initNativeSpoofer(
        JNIEnv *env,
        jclass clazz,
        jstring brand,
        jstring manufacturer,
        jstring model,
        jstring product,
        jstring device,
        jstring board,
        jstring hardware,
        jstring fingerprint,
        jstring serial,
        jstring releaseVersion,
        jint sdkInt,
        jstring securityPatch) {

    std::lock_guard<std::mutex> lock(g_config_mutex);

    auto get_str = [&](jstring jstr) -> std::string {
        if (!jstr) return "";
        const char *c_str = env->GetStringUTFChars(jstr, nullptr);
        std::string s = c_str ? c_str : "";
        if (c_str) env->ReleaseStringUTFChars(jstr, c_str);
        return s;
    };

    g_config.brand = get_str(brand);
    g_config.manufacturer = get_str(manufacturer);
    g_config.model = get_str(model);
    g_config.product = get_str(product);
    g_config.device = get_str(device);
    g_config.board = get_str(board);
    g_config.hardware = get_str(hardware);
    g_config.fingerprint = get_str(fingerprint);
    g_config.serial = get_str(serial);
    g_config.releaseVersion = get_str(releaseVersion);
    g_config.sdkInt = std::to_string(sdkInt);
    g_config.securityPatch = get_str(securityPatch);
    g_config.active = true;

    LOGI("Native Spoofer Configured: %s %s (%s)", g_config.brand.c_str(), g_config.model.c_str(), g_config.device.c_str());

    // Resolve libc.so symbols for native property Interception
    void *libc_handle = dlopen("libc.so", RTLD_NOW);
    if (libc_handle) {
        if (!orig_system_property_get) {
            orig_system_property_get = reinterpret_cast<int (*)(const char*, char*)>(dlsym(libc_handle, "__system_property_get"));
        }
        if (!orig_system_property_read_callback) {
            orig_system_property_read_callback = reinterpret_cast<void (*)(const prop_info*, void (*)(void*, const char*, const char*, uint32_t), void*)>(dlsym(libc_handle, "__system_property_read_callback"));
        }
    }
}
