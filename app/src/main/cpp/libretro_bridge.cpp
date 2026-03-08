#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <android/log.h>
#include "libretro.h"

#define LOG_TAG "LibretroBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct {
    void *handle;
    retro_init_t init;
    retro_deinit_t deinit;
    retro_run_t run;
    retro_load_game_t load_game;
    retro_unload_game_t unload_game;
    retro_set_environment_t set_environment;
    retro_set_video_refresh_t set_video_refresh;
    retro_set_audio_sample_t set_audio_sample;
    retro_set_audio_sample_batch_t set_audio_sample_batch;
    retro_set_input_poll_t set_input_poll;
    retro_set_input_state_t set_input_state;
    retro_get_system_info_t get_system_info;
    retro_get_system_av_info_t get_system_av_info;
    retro_serialize_size_t serialize_size;
    retro_serialize_t serialize;
    retro_unserialize_t unserialize;
    retro_get_memory_data_t get_memory_data;
    retro_get_memory_size_t get_memory_size;
    retro_reset_t reset;
} core;

// State shared with callbacks
static int16_t g_input_state = 0;
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_RGB565;

// Frame buffer written by video callback, read by renderer
static uint8_t *g_frame_buf = nullptr;
static unsigned g_frame_width = 0;
static unsigned g_frame_height = 0;
static size_t g_frame_pitch = 0;
static bool g_frame_ready = false;

// Audio state
static JavaVM *g_jvm = nullptr;
static jobject g_audio_obj = nullptr;
static jmethodID g_audio_write_method = nullptr;

// Paths
static char g_system_dir[512] = {0};
static char g_save_dir[512] = {0};

// --- Libretro callbacks ---

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  prio = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_vprint(prio, "LibretroCore", fmt, args);
    va_end(args);
}

static bool environment_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            g_pixel_format = *(const unsigned *)data;
            LOGI("Pixel format set to: %u", g_pixel_format);
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = g_system_dir;
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = g_save_dir;
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto *cb = (struct retro_log_callback *)data;
            cb->log = core_log;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE:
            return false;

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false;
            return true;

        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *)data = 0; // RETRO_LANGUAGE_ENGLISH
            return true;

        default:
            LOGI("Unhandled env cmd: %u", cmd);
            return false;
    }
}

static void video_refresh_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

    size_t bpp = (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) ? 4 : 2;
    size_t needed = width * height * bpp;

    if (!g_frame_buf || g_frame_width != width || g_frame_height != height) {
        free(g_frame_buf);
        g_frame_buf = (uint8_t *)malloc(needed);
        g_frame_width = width;
        g_frame_height = height;
    }

    // Copy row by row since pitch may differ from width * bpp
    const uint8_t *src = (const uint8_t *)data;
    uint8_t *dst = g_frame_buf;
    size_t row_bytes = width * bpp;
    for (unsigned y = 0; y < height; y++) {
        memcpy(dst, src, row_bytes);
        src += pitch;
        dst += row_bytes;
    }
    g_frame_pitch = row_bytes;
    g_frame_ready = true;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    // Single sample callback - rarely used, but handle it
    int16_t buf[2] = {left, right};
    if (!g_audio_obj || !g_jvm) return;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    jshortArray arr = env->NewShortArray(2);
    env->SetShortArrayRegion(arr, 0, 2, buf);
    env->CallVoidMethod(g_audio_obj, g_audio_write_method, arr, 2);
    env->DeleteLocalRef(arr);

    if (attached) g_jvm->DetachCurrentThread();
}

static size_t audio_sample_batch_cb(const int16_t *data, size_t frames) {
    if (!g_audio_obj || !g_jvm || frames == 0) return frames;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    jint count = (jint)(frames * 2); // stereo interleaved
    jshortArray arr = env->NewShortArray(count);
    env->SetShortArrayRegion(arr, 0, count, data);
    env->CallVoidMethod(g_audio_obj, g_audio_write_method, arr, count);
    env->DeleteLocalRef(arr);

    if (attached) g_jvm->DetachCurrentThread();
    return frames;
}

static void input_poll_cb(void) {
    // No-op - input state is set from Kotlin side before retro_run
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    return (g_input_state >> id) & 1;
}

// --- JNI helpers ---

#define LOAD_SYM(name) do { \
    core.name = (retro_##name##_t)dlsym(core.handle, "retro_" #name); \
    if (!core.name) { LOGE("Missing symbol: retro_%s", #name); } \
} while(0)

// --- JNI exports ---

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeLoadCore(JNIEnv *env, jobject, jstring corePath) {
    const char *path = env->GetStringUTFChars(corePath, nullptr);
    core.handle = dlopen(path, RTLD_LAZY);
    env->ReleaseStringUTFChars(corePath, path);

    if (!core.handle) {
        LOGE("dlopen failed: %s", dlerror());
        return JNI_FALSE;
    }

    LOAD_SYM(init);
    LOAD_SYM(deinit);
    LOAD_SYM(run);
    LOAD_SYM(load_game);
    LOAD_SYM(unload_game);
    LOAD_SYM(set_environment);
    LOAD_SYM(set_video_refresh);
    LOAD_SYM(set_audio_sample);
    LOAD_SYM(set_audio_sample_batch);
    LOAD_SYM(set_input_poll);
    LOAD_SYM(set_input_state);
    LOAD_SYM(get_system_info);
    LOAD_SYM(get_system_av_info);
    LOAD_SYM(serialize_size);
    LOAD_SYM(serialize);
    LOAD_SYM(unserialize);
    LOAD_SYM(get_memory_data);
    LOAD_SYM(get_memory_size);
    LOAD_SYM(reset);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeInit(JNIEnv *env, jobject,
        jstring systemDir, jstring saveDir) {
    const char *sys = env->GetStringUTFChars(systemDir, nullptr);
    const char *sav = env->GetStringUTFChars(saveDir, nullptr);
    strncpy(g_system_dir, sys, sizeof(g_system_dir) - 1);
    strncpy(g_save_dir, sav, sizeof(g_save_dir) - 1);
    env->ReleaseStringUTFChars(systemDir, sys);
    env->ReleaseStringUTFChars(saveDir, sav);

    core.set_environment(environment_cb);
    core.set_video_refresh(video_refresh_cb);
    core.set_audio_sample(audio_sample_cb);
    core.set_audio_sample_batch(audio_sample_batch_cb);
    core.set_input_poll(input_poll_cb);
    core.set_input_state(input_state_cb);
    core.init();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSetAudioCallback(JNIEnv *env, jobject,
        jobject audioObj) {
    if (g_audio_obj) env->DeleteGlobalRef(g_audio_obj);
    g_audio_obj = env->NewGlobalRef(audioObj);
    jclass cls = env->GetObjectClass(audioObj);
    g_audio_write_method = env->GetMethodID(cls, "writeSamples", "([SI)V");
}

JNIEXPORT jintArray JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeLoadGame(JNIEnv *env, jobject, jstring romPath) {
    const char *path = env->GetStringUTFChars(romPath, nullptr);

    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGE("Failed to open ROM: %s", path);
        env->ReleaseStringUTFChars(romPath, path);
        return nullptr;
    }

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *rom_data = malloc(size);
    fread(rom_data, 1, size, f);
    fclose(f);

    struct retro_game_info game_info = {0};
    game_info.path = path;
    game_info.data = rom_data;
    game_info.size = size;

    bool ok = core.load_game(&game_info);
    free(rom_data);
    env->ReleaseStringUTFChars(romPath, path);

    if (!ok) {
        LOGE("retro_load_game failed");
        return nullptr;
    }

    struct retro_system_av_info av_info;
    core.get_system_av_info(&av_info);

    LOGI("Game loaded: %ux%u @ %.2f fps, audio %.0f Hz",
         av_info.geometry.base_width, av_info.geometry.base_height,
         av_info.timing.fps, av_info.timing.sample_rate);

    // Return [width, height, fps*100, sampleRate]
    jintArray result = env->NewIntArray(4);
    jint vals[4] = {
        (jint)av_info.geometry.base_width,
        (jint)av_info.geometry.base_height,
        (jint)(av_info.timing.fps * 100),
        (jint)av_info.timing.sample_rate
    };
    env->SetIntArrayRegion(result, 0, 4, vals);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeRun(JNIEnv *, jobject) {
    core.run();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSetInput(JNIEnv *, jobject, jint mask) {
    g_input_state = (int16_t)mask;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetPixelFormat(JNIEnv *, jobject) {
    return (jint)g_pixel_format;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetFrameWidth(JNIEnv *, jobject) {
    return (jint)g_frame_width;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetFrameHeight(JNIEnv *, jobject) {
    return (jint)g_frame_height;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeHasNewFrame(JNIEnv *, jobject) {
    return g_frame_ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeCopyFrame(JNIEnv *env, jobject, jobject buffer) {
    if (!g_frame_buf || !g_frame_ready) return;
    void *dst = env->GetDirectBufferAddress(buffer);
    if (!dst) return;
    size_t bpp = (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) ? 4 : 2;
    size_t size = g_frame_width * g_frame_height * bpp;
    memcpy(dst, g_frame_buf, size);
    g_frame_ready = false;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeCopyLastFrame(JNIEnv *env, jobject, jobject buffer) {
    if (!g_frame_buf) return;
    void *dst = env->GetDirectBufferAddress(buffer);
    if (!dst) return;
    size_t bpp = (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) ? 4 : 2;
    size_t size = g_frame_width * g_frame_height * bpp;
    memcpy(dst, g_frame_buf, size);
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSaveState(JNIEnv *env, jobject, jstring path) {
    size_t size = core.serialize_size();
    if (size == 0) return JNI_FALSE;

    void *buf = malloc(size);
    if (!core.serialize(buf, size)) {
        free(buf);
        return JNI_FALSE;
    }

    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "wb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) { free(buf); return JNI_FALSE; }

    fwrite(buf, 1, size, f);
    fclose(f);
    free(buf);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeLoadState(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "rb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *buf = malloc(size);
    fread(buf, 1, size, f);
    fclose(f);

    bool ok = core.unserialize(buf, size);
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSaveSRAM(JNIEnv *env, jobject, jstring path) {
    void *data = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "wb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;

    fwrite(data, 1, size, f);
    fclose(f);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeLoadSRAM(JNIEnv *env, jobject, jstring path) {
    void *data = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char *p = env->GetStringUTFChars(path, nullptr);
    FILE *f = fopen(p, "rb");
    env->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;

    fread(data, 1, size, f);
    fclose(f);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeUnloadGame(JNIEnv *, jobject) {
    core.unload_game();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeDeinit(JNIEnv *env, jobject) {
    core.deinit();
    if (core.handle) {
        dlclose(core.handle);
        core.handle = nullptr;
    }
    if (g_audio_obj) {
        env->DeleteGlobalRef(g_audio_obj);
        g_audio_obj = nullptr;
    }
    free(g_frame_buf);
    g_frame_buf = nullptr;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_ready = false;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeReset(JNIEnv *, jobject) {
    if (core.reset) core.reset();
}

} // extern "C"
