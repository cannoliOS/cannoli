#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <string>
#include <map>
#include <vector>
#include <android/log.h>
#include "libretro.h"
#include "frame_buffer.h"

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
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;

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

// Core options
struct CoreOptionValue {
    std::string value;
    std::string label;
};
struct CoreOption {
    std::string key;
    std::string desc;
    std::string info;
    std::string category;
    std::vector<CoreOptionValue> values;
    std::string selected;
};
struct CoreCategory {
    std::string key;
    std::string desc;
    std::string info;
};
static std::vector<CoreOption> g_core_options;
static std::vector<CoreCategory> g_core_categories;
static std::map<std::string, std::string> g_option_overrides;
static bool g_options_dirty = false;

// Disk control
static struct retro_disk_control_callback g_disk_control = {0};
static bool g_has_disk_control = false;
static const char *(*g_get_image_label)(unsigned index) = nullptr;

// Memory map (for RetroAchievements)
static struct retro_memory_map g_memory_map = {0};
static struct retro_memory_descriptor *g_memory_descriptors = nullptr;
static unsigned g_memory_descriptor_count = 0;

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
    cmd &= ~0x10000U; /* strip RETRO_ENVIRONMENT_EXPERIMENTAL flag */
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_OVERSCAN:
            *(bool *)data = true;
            return true;

        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            return true;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 2;
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

        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            g_core_options.clear();
            g_core_categories.clear();
            auto *vars = (const struct retro_variable *)data;
            while (vars && vars->key) {
                CoreOption opt;
                opt.key = vars->key;
                const char *desc = vars->value;
                const char *pipe = strchr(desc, ';');
                if (pipe) {
                    opt.desc = std::string(desc, pipe - desc);
                    const char *p = pipe + 1;
                    while (*p == ' ') p++;
                    std::string valstr(p);
                    size_t pos = 0;
                    while ((pos = valstr.find('|')) != std::string::npos) {
                        std::string v = valstr.substr(0, pos);
                        opt.values.push_back({v, v});
                        valstr.erase(0, pos + 1);
                    }
                    if (!valstr.empty()) opt.values.push_back({valstr, valstr});
                    if (!opt.values.empty()) opt.selected = opt.values[0].value;
                } else {
                    opt.desc = desc;
                }
                g_core_options.push_back(opt);
                vars++;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            g_core_options.clear();
            g_core_categories.clear();
            auto *opts = (const struct retro_core_options_v2 *)data;
            if (opts) {
                if (opts->categories) {
                    auto *cat = opts->categories;
                    while (cat->key) {
                        g_core_categories.push_back({
                            cat->key,
                            cat->desc ? cat->desc : cat->key,
                            cat->info ? cat->info : ""
                        });
                        cat++;
                    }
                }
                if (opts->definitions) {
                    auto *def = opts->definitions;
                    while (def->key) {
                        CoreOption opt;
                        opt.key = def->key;
                        opt.desc = (def->desc_categorized && def->desc_categorized[0])
                                   ? def->desc_categorized : (def->desc ? def->desc : def->key);
                        const char *info_src = (def->info_categorized && def->info_categorized[0])
                                   ? def->info_categorized : def->info;
                        opt.info = info_src ? info_src : "";
                        opt.category = def->category_key ? def->category_key : "";
                        for (int i = 0; i < 128 && def->values[i].value; i++) {
                            const char *label = def->values[i].label;
                            std::string lbl = (label && label[0]) ? label : def->values[i].value;
                            opt.values.push_back({def->values[i].value, lbl});
                        }
                        opt.selected = def->default_value ? def->default_value :
                                       (!opt.values.empty() ? opt.values[0].value : "");
                        g_core_options.push_back(opt);
                        def++;
                    }
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            auto *intl = (const struct retro_core_options_v2_intl *)data;
            if (intl && intl->us) {
                // Reuse the v2 handler by faking the environment call
                environment_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2, (void *)intl->us);
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return false;

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto *var = (struct retro_variable *)data;
            if (!var || !var->key) return false;
            auto it = g_option_overrides.find(var->key);
            if (it != g_option_overrides.end()) {
                var->value = it->second.c_str();
                return true;
            }
            for (auto &opt : g_core_options) {
                if (opt.key == var->key) {
                    var->value = opt.selected.c_str();
                    return true;
                }
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = g_options_dirty;
            g_options_dirty = false;
            return true;

        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;

        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
            auto *cb = (struct retro_disk_control_callback *)data;
            g_disk_control = *cb;
            g_has_disk_control = true;
            g_get_image_label = nullptr;
            LOGI("Disk control interface set");
            return true;
        }

        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: {
            auto *cb = (struct retro_disk_control_ext_callback *)data;
            g_disk_control.set_eject_state = cb->set_eject_state;
            g_disk_control.get_eject_state = cb->get_eject_state;
            g_disk_control.get_image_index = cb->get_image_index;
            g_disk_control.set_image_index = cb->set_image_index;
            g_disk_control.get_num_images = cb->get_num_images;
            g_disk_control.replace_image_index = cb->replace_image_index;
            g_disk_control.add_image_index = cb->add_image_index;
            g_has_disk_control = true;
            g_get_image_label = cb->get_image_label;
            LOGI("Disk control ext interface set");
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *)data = 0; // RETRO_LANGUAGE_ENGLISH
            return true;

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: {
            const struct retro_memory_map *mmap = (const struct retro_memory_map *)data;
            free(g_memory_descriptors);
            g_memory_descriptor_count = mmap->num_descriptors;
            g_memory_descriptors = (struct retro_memory_descriptor *)calloc(
                mmap->num_descriptors, sizeof(struct retro_memory_descriptor));
            memcpy(g_memory_descriptors, mmap->descriptors,
                mmap->num_descriptors * sizeof(struct retro_memory_descriptor));
            g_memory_map.descriptors = g_memory_descriptors;
            g_memory_map.num_descriptors = g_memory_descriptor_count;
            LOGE("Memory map set: %u descriptors", g_memory_descriptor_count);
            for (unsigned i = 0; i < g_memory_descriptor_count; i++) {
                const struct retro_memory_descriptor *d = &g_memory_descriptors[i];
                LOGE("  desc[%u]: ptr=%p start=0x%zx len=0x%zx select=0x%zx disconnect=0x%zx offset=0x%zx",
                    i, d->ptr, d->start, d->len, d->select, d->disconnect, d->offset);
            }
            return true;
        }

        default:
            LOGE("Unhandled env cmd: %u", cmd);
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

    const uint8_t *src = (const uint8_t *)data;
    uint8_t *dst = g_frame_buf;
    size_t row_bytes = width * bpp;

    if (g_pixel_format == RETRO_PIXEL_FORMAT_0RGB1555) {
        // Convert 0RGB1555 to RGB565 in-place so the renderer only needs two paths
        for (unsigned y = 0; y < height; y++) {
            const uint16_t *src16 = (const uint16_t *)src;
            uint16_t *dst16 = (uint16_t *)dst;
            for (unsigned x = 0; x < width; x++) {
                uint16_t px = src16[x];
                unsigned r = (px >> 10) & 0x1F;
                unsigned g = (px >> 5) & 0x1F;
                unsigned b = px & 0x1F;
                dst16[x] = (r << 11) | (g << 6) | (g >> 4) | b;
            }
            src += pitch;
            dst += row_bytes;
        }
    } else if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        // Convert XRGB8888 (0x00RRGGBB) to ABGR so GL_RGBA/GL_UNSIGNED_BYTE reads correctly
        for (unsigned y = 0; y < height; y++) {
            const uint32_t *src32 = (const uint32_t *)src;
            uint32_t *dst32 = (uint32_t *)dst;
            for (unsigned x = 0; x < width; x++) {
                uint32_t px = src32[x];
                uint32_t r = (px >> 16) & 0xFF;
                uint32_t g = (px >> 8) & 0xFF;
                uint32_t b = px & 0xFF;
                dst32[x] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
            src += pitch;
            dst += row_bytes;
        }
    } else {
        for (unsigned y = 0; y < height; y++) {
            memcpy(dst, src, row_bytes);
            src += pitch;
            dst += row_bytes;
        }
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
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return g_input_state;
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

/* ra_integration.c */
extern "C" void ra_process_frame(void);

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeRun(JNIEnv *, jobject) {
    core.run();
    ra_process_frame();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSetInput(JNIEnv *, jobject, jint mask) {
    g_input_state = (int16_t)mask;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetPixelFormat(JNIEnv *, jobject) {
    // 0RGB1555 is converted to RGB565 in video_refresh_cb
    unsigned effective = (g_pixel_format == RETRO_PIXEL_FORMAT_0RGB1555)
        ? RETRO_PIXEL_FORMAT_RGB565 : g_pixel_format;
    return (jint)effective;
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
    memset(&g_disk_control, 0, sizeof(g_disk_control));
    g_has_disk_control = false;
    g_get_image_label = nullptr;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeReset(JNIEnv *, jobject) {
    if (core.reset) core.reset();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetCoreOptions(JNIEnv *env, jobject) {
    jclass strClass = env->FindClass("java/lang/String");
    int count = (int)g_core_options.size();
    jobjectArray result = env->NewObjectArray(count * 7, strClass, nullptr);
    for (int i = 0; i < count; i++) {
        auto &opt = g_core_options[i];
        std::string vals;
        std::string labels;
        for (size_t j = 0; j < opt.values.size(); j++) {
            if (j > 0) { vals += '|'; labels += '|'; }
            vals += opt.values[j].value;
            labels += opt.values[j].label;
        }
        auto it = g_option_overrides.find(opt.key);
        const std::string &sel = (it != g_option_overrides.end()) ? it->second : opt.selected;
        env->SetObjectArrayElement(result, i * 7 + 0, env->NewStringUTF(opt.key.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 1, env->NewStringUTF(opt.desc.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 2, env->NewStringUTF(vals.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 3, env->NewStringUTF(sel.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 4, env->NewStringUTF(opt.category.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 5, env->NewStringUTF(labels.c_str()));
        env->SetObjectArrayElement(result, i * 7 + 6, env->NewStringUTF(opt.info.c_str()));
    }
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetCoreCategories(JNIEnv *env, jobject) {
    jclass strClass = env->FindClass("java/lang/String");
    int count = (int)g_core_categories.size();
    jobjectArray result = env->NewObjectArray(count * 3, strClass, nullptr);
    for (int i = 0; i < count; i++) {
        env->SetObjectArrayElement(result, i * 3 + 0, env->NewStringUTF(g_core_categories[i].key.c_str()));
        env->SetObjectArrayElement(result, i * 3 + 1, env->NewStringUTF(g_core_categories[i].desc.c_str()));
        env->SetObjectArrayElement(result, i * 3 + 2, env->NewStringUTF(g_core_categories[i].info.c_str()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSetCoreOption(JNIEnv *env, jobject, jstring key, jstring value) {
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    g_option_overrides[k] = v;
    g_options_dirty = true;
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetSystemInfo(JNIEnv *env, jobject) {
    struct retro_system_info info = {0};
    core.get_system_info(&info);

    jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(info.library_name ? info.library_name : ""));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(info.library_version ? info.library_version : ""));
    return result;
}

JNIEXPORT jfloat JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetAspectRatio(JNIEnv *, jobject) {
    struct retro_system_av_info av_info;
    core.get_system_av_info(&av_info);
    float ar = av_info.geometry.aspect_ratio;
    if (ar <= 0.0f) {
        ar = (float)av_info.geometry.base_width / (float)av_info.geometry.base_height;
    }
    return ar;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetDiskCount(JNIEnv *, jobject) {
    if (!g_has_disk_control || !g_disk_control.get_num_images) return 0;
    return (jint)g_disk_control.get_num_images();
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetDiskIndex(JNIEnv *, jobject) {
    if (!g_has_disk_control || !g_disk_control.get_image_index) return 0;
    return (jint)g_disk_control.get_image_index();
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeSetDiskIndex(JNIEnv *, jobject, jint index) {
    if (!g_has_disk_control || !g_disk_control.set_eject_state || !g_disk_control.set_image_index) return JNI_FALSE;
    g_disk_control.set_eject_state(true);
    bool ok = g_disk_control.set_image_index((unsigned)index);
    g_disk_control.set_eject_state(false);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_LibretroRunner_nativeGetDiskLabel(JNIEnv *env, jobject, jint index) {
    if (g_get_image_label) {
        const char *label = g_get_image_label((unsigned)index);
        if (label && label[0]) return env->NewStringUTF(label);
    }
    return nullptr;
}

} // extern "C"

static FrameBuffer g_shared_frame;

extern "C" FrameBuffer *getFrameBuffer() {
    g_shared_frame.data = g_frame_buf;
    g_shared_frame.width = g_frame_width;
    g_shared_frame.height = g_frame_height;
    g_shared_frame.pitch = g_frame_pitch;
    g_shared_frame.pixel_format = g_pixel_format;
    g_shared_frame.ready = g_frame_ready;
    return &g_shared_frame;
}

extern "C" void markFrameConsumed() {
    g_frame_ready = false;
}

extern "C" const struct retro_memory_map *bridge_get_memory_map(void) {
    return g_memory_descriptor_count > 0 ? &g_memory_map : nullptr;
}

extern "C" void *bridge_get_memory_data(unsigned id) {
    return core.get_memory_data ? core.get_memory_data(id) : nullptr;
}

extern "C" size_t bridge_get_memory_size(unsigned id) {
    return core.get_memory_size ? core.get_memory_size(id) : 0;
}
