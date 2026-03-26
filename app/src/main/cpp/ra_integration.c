#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_libretro.h"
#include "rc_api_runtime.h"
#include "rc_api_user.h"

#define LOG_TAG "RA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Bridge functions from libretro_bridge.cpp */
extern void *bridge_get_memory_data(unsigned id);
extern size_t bridge_get_memory_size(unsigned id);
extern const struct retro_memory_map *bridge_get_memory_map(void);

/* ---- state ---- */
static rc_client_t *g_client = NULL;
static JavaVM *g_jvm = NULL;
static jobject g_manager = NULL;
static jmethodID g_onServerCall = NULL;
static jmethodID g_onEvent = NULL;
static jmethodID g_onLoginResult = NULL;
static pthread_mutex_t g_queue_mutex = PTHREAD_MUTEX_INITIALIZER;

static char *g_pending_rom_path = NULL;
static uint32_t g_pending_console_id = 0;
static uint32_t g_pending_game_id = 0;
static rc_libretro_memory_regions_t g_memory_regions;
static int g_memory_initialized = 0;

static void ra_load_game_callback(int result, const char *error_message, rc_client_t *client, void *userdata);

/* ---- HTTP response queue ---- */
typedef struct QueuedResponse {
    rc_client_server_callback_t callback;
    void *callback_data;
    char *body;
    size_t body_len;
    int http_status;
    struct QueuedResponse *next;
} QueuedResponse;

static QueuedResponse *g_response_head = NULL;
static QueuedResponse *g_response_tail = NULL;

static void process_queued_responses(void) {
    pthread_mutex_lock(&g_queue_mutex);
    QueuedResponse *head = g_response_head;
    g_response_head = NULL;
    g_response_tail = NULL;
    pthread_mutex_unlock(&g_queue_mutex);

    while (head) {
        QueuedResponse *next = head->next;
        if (head->callback) {
            rc_api_server_response_t response;
            memset(&response, 0, sizeof(response));
            response.body = head->body;
            response.body_length = head->body_len;
            response.http_status_code = head->http_status;
            head->callback(&response, head->callback_data);
        }
        free(head->body);
        free(head);
        head = next;
    }
}

/* ---- rcheevos callbacks ---- */

static void ra_get_core_memory(unsigned id, rc_libretro_core_memory_info_t *info) {
    info->data = (uint8_t *)bridge_get_memory_data(id);
    info->size = bridge_get_memory_size(id);
    LOGI("get_core_memory(%u): data=%p size=%zu", id, info->data, info->size);
}

static uint32_t ra_read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *client) {
    (void)client;
    if (!g_memory_initialized) return 0;
    return rc_libretro_memory_read(&g_memory_regions, address, buffer, num_bytes);
    return num_bytes;
}

static void ra_server_call(const rc_api_request_t *request,
                           rc_client_server_callback_t callback, void *callback_data,
                           rc_client_t *client) {
    (void)client;

    /* Intercept hash resolve when we have a game ID override */
    if (g_pending_game_id && request->url && strstr(request->url, "r=gameid")) {
        char body[64];
        snprintf(body, sizeof(body), "{\"Success\":true,\"GameID\":%u}", g_pending_game_id);
        LOGI("Intercepted hash resolve, returning game ID %u", g_pending_game_id);
        g_pending_game_id = 0;
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.body = body;
        response.body_length = strlen(body);
        response.http_status_code = 200;
        callback(&response, callback_data);
        return;
    }

    if (!g_jvm || !g_manager) {
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&response, callback_data);
        return;
    }

    /* Store callback info for later response */
    QueuedResponse *qr = (QueuedResponse *)calloc(1, sizeof(QueuedResponse));
    qr->callback = callback;
    qr->callback_data = callback_data;

    LOGI("server_call: %s", request->url);
    /* Call Kotlin to execute HTTP */
    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    jstring jUrl = (*env)->NewStringUTF(env, request->url);
    jstring jPost = request->post_data ? (*env)->NewStringUTF(env, request->post_data) : NULL;

    (*env)->CallVoidMethod(env, g_manager, g_onServerCall, jUrl, jPost, (jlong)(uintptr_t)qr);

    (*env)->DeleteLocalRef(env, jUrl);
    if (jPost) (*env)->DeleteLocalRef(env, jPost);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void ra_event_handler(const rc_client_event_t *event, rc_client_t *client) {
    (void)client;
    if (!g_jvm || !g_manager) return;

    if (event->type != RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED &&
        event->type != RC_CLIENT_EVENT_GAME_COMPLETED)
        return;

    const char *title = "";
    const char *desc = "";
    int points = 0;
    int type = event->type;

    if (event->achievement) {
        title = event->achievement->title;
        desc = event->achievement->description;
        points = event->achievement->points;
    }

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    jstring jTitle = (*env)->NewStringUTF(env, title);
    jstring jDesc = (*env)->NewStringUTF(env, desc);
    (*env)->CallVoidMethod(env, g_manager, g_onEvent, type, jTitle, jDesc, points);
    (*env)->DeleteLocalRef(env, jTitle);
    (*env)->DeleteLocalRef(env, jDesc);

    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void ra_log(const char *message, const rc_client_t *client) {
    (void)client;
    LOGI("%s", message);
}

/* ---- JNI functions ---- */

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeInit(JNIEnv *env, jobject thiz) {
    (*env)->GetJavaVM(env, &g_jvm);
    g_manager = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_onServerCall = (*env)->GetMethodID(env, cls, "onServerCall", "(Ljava/lang/String;Ljava/lang/String;J)V");
    g_onEvent = (*env)->GetMethodID(env, cls, "onAchievementEvent", "(ILjava/lang/String;Ljava/lang/String;I)V");
    g_onLoginResult = (*env)->GetMethodID(env, cls, "onLoginResult", "(ZLjava/lang/String;Ljava/lang/String;)V");

    g_client = rc_client_create(ra_read_memory, ra_server_call);
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, ra_log);
    rc_client_set_hardcore_enabled(g_client, 0);
    rc_client_set_event_handler(g_client, ra_event_handler);

    LOGI("RetroAchievements initialized");
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeDestroy(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (g_client) {
        rc_client_destroy(g_client);
        g_client = NULL;
    }
    if (g_manager) {
        (*env)->DeleteGlobalRef(env, g_manager);
        g_manager = NULL;
    }
    LOGI("RetroAchievements destroyed");
}

static void ra_login_callback(int result, const char *error_message, rc_client_t *client, void *userdata) {
    (void)client; (void)userdata;
    if (!g_jvm || !g_manager) return;

    JNIEnv *env;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        attached = 1;
    }

    if (result == RC_OK) {
        const rc_client_user_t *user = rc_client_get_user_info(g_client);
        LOGI("Logged in as %s (score: %u)", user->display_name, user->score);
        jstring jName = (*env)->NewStringUTF(env, user->display_name);
        jstring jToken = (*env)->NewStringUTF(env, user->token);
        (*env)->CallVoidMethod(env, g_manager, g_onLoginResult, JNI_TRUE, jName, jToken);
        (*env)->DeleteLocalRef(env, jName);
        (*env)->DeleteLocalRef(env, jToken);

        if (g_pending_rom_path || g_pending_game_id) {
            const struct retro_memory_map *mmap = bridge_get_memory_map();
            rc_libretro_memory_init(&g_memory_regions, mmap, ra_get_core_memory, g_pending_console_id);
            g_memory_initialized = 1;

            if (g_pending_game_id) {
                LOGI("Loading game by ID: %u (console %u)", g_pending_game_id, g_pending_console_id);
                char hash[33];
                snprintf(hash, sizeof(hash), "CANNOLI_%010u", g_pending_game_id);
                /* Register a fake hash→gameID mapping so rc_client can load by "hash" */
                rc_client_begin_load_game(g_client, hash, ra_load_game_callback, NULL);
                g_pending_game_id = 0;
            } else {
                LOGI("Loading pending game: %s (console %u)", g_pending_rom_path, g_pending_console_id);
                rc_client_begin_identify_and_load_game(g_client, g_pending_console_id,
                    g_pending_rom_path, NULL, 0, ra_load_game_callback, NULL);
                free(g_pending_rom_path);
                g_pending_rom_path = NULL;
            }
        }
    } else {
        LOGE("Login failed: %s", error_message ? error_message : "unknown error");
        jstring jErr = (*env)->NewStringUTF(env, error_message ? error_message : "Login failed");
        (*env)->CallVoidMethod(env, g_manager, g_onLoginResult, JNI_FALSE, jErr, NULL);
        (*env)->DeleteLocalRef(env, jErr);
    }

    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoginWithToken(JNIEnv *env, jobject thiz,
        jstring username, jstring token) {
    (void)thiz;
    if (!g_client) return;
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *tok = (*env)->GetStringUTFChars(env, token, NULL);
    rc_client_begin_login_with_token(g_client, user, tok, ra_login_callback, NULL);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, token, tok);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoginWithPassword(JNIEnv *env, jobject thiz,
        jstring username, jstring password) {
    (void)thiz;
    if (!g_client) return;
    const char *user = (*env)->GetStringUTFChars(env, username, NULL);
    const char *pass = (*env)->GetStringUTFChars(env, password, NULL);
    rc_client_begin_login_with_password(g_client, user, pass, ra_login_callback, NULL);
    (*env)->ReleaseStringUTFChars(env, username, user);
    (*env)->ReleaseStringUTFChars(env, password, pass);
}

static void ra_load_game_callback(int result, const char *error_message, rc_client_t *client, void *userdata) {
    (void)client; (void)userdata;
    if (result == RC_OK) {
        const rc_client_game_t *game = rc_client_get_game_info(g_client);
        LOGI("Game loaded: %s (id: %u)", game->title, game->id);
    } else {
        LOGE("Game load failed: %s", error_message ? error_message : "unknown error");
    }
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoadGame(JNIEnv *env, jobject thiz,
        jstring romPath, jint consoleId) {
    (void)thiz;
    if (!g_client) return;
    const char *path = (*env)->GetStringUTFChars(env, romPath, NULL);
    free(g_pending_rom_path);
    g_pending_rom_path = strdup(path);
    g_pending_console_id = (uint32_t)consoleId;
    (*env)->ReleaseStringUTFChars(env, romPath, path);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeLoadGameById(JNIEnv *env, jobject thiz,
        jint gameId, jint consoleId) {
    (void)env; (void)thiz;
    if (!g_client) return;
    free(g_pending_rom_path);
    g_pending_rom_path = NULL;
    g_pending_console_id = (uint32_t)consoleId;
    g_pending_game_id = (uint32_t)gameId;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeUnloadGame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_memory_initialized) {
        rc_libretro_memory_destroy(&g_memory_regions);
        g_memory_initialized = 0;
    }
    if (g_client) rc_client_unload_game(g_client);
}

void ra_process_frame(void) {
    if (!g_client) return;
    process_queued_responses();
    if (rc_client_is_game_loaded(g_client))
        rc_client_do_frame(g_client);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeDoFrame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    ra_process_frame();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeIdle(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_client) return;
    process_queued_responses();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeReset(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_client) rc_client_reset(g_client);
}

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeIsLoggedIn(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_client) return JNI_FALSE;
    const rc_client_user_t *user = rc_client_get_user_info(g_client);
    return (user && user->token[0]) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetUsername(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_client) return (*env)->NewStringUTF(env, "");
    const rc_client_user_t *user = rc_client_get_user_info(g_client);
    return (*env)->NewStringUTF(env, user ? user->display_name : "");
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeHttpResponse(JNIEnv *env, jobject thiz,
        jlong requestPtr, jstring body, jint httpStatus) {
    (void)thiz;
    QueuedResponse *qr = (QueuedResponse *)(uintptr_t)requestPtr;
    if (!qr) return;

    const char *bodyStr = body ? (*env)->GetStringUTFChars(env, body, NULL) : "";
    size_t len = strlen(bodyStr);
    qr->body = (char *)malloc(len + 1);
    memcpy(qr->body, bodyStr, len + 1);
    qr->body_len = len;
    qr->http_status = httpStatus;
    if (body) (*env)->ReleaseStringUTFChars(env, body, bodyStr);

    pthread_mutex_lock(&g_queue_mutex);
    qr->next = NULL;
    if (g_response_tail) g_response_tail->next = qr;
    else g_response_head = qr;
    g_response_tail = qr;
    pthread_mutex_unlock(&g_queue_mutex);
}

/* Returns pipe-delimited flat string: id|title|description|points|unlocked|state|unlockTime\n per achievement */
JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetAchievementData(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return (*env)->NewStringUTF(env, "");

    rc_client_achievement_list_t *list = rc_client_create_achievement_list(g_client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (!list) return (*env)->NewStringUTF(env, "");

    size_t cap = 4096;
    char *buf = (char *)malloc(cap);
    size_t pos = 0;

    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_achievement_bucket_t *bucket = &list->buckets[b];
        for (uint32_t a = 0; a < bucket->num_achievements; a++) {
            const rc_client_achievement_t *ach = bucket->achievements[a];
            if (ach->points == 0 && ach->id == 0) continue;
            int softcore = (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_SOFTCORE) ? 1 : 0;
            if (!softcore && (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_HARDCORE)) continue;

            if (pos + 512 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            pos += snprintf(buf + pos, cap - pos, "%u|%s|%s|%u|%d|%d|%ld\n",
                ach->id,
                ach->title ? ach->title : "",
                ach->description ? ach->description : "",
                ach->points, softcore, ach->state, (long)ach->unlock_time);
        }
    }
    if (pos > 0) buf[pos - 1] = '\0'; /* trim trailing newline */
    else buf[0] = '\0';

    rc_client_destroy_achievement_list(list);
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

static void ra_award_callback(int result, const char *error_message, rc_client_t *client, void *userdata) {
    (void)client; (void)userdata;
    if (result == RC_OK) LOGI("Achievement manually awarded");
    else LOGE("Manual award failed: %s", error_message ? error_message : "unknown");
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeManualUnlock(JNIEnv *env, jobject thiz, jint achievementId) {
    (void)thiz;
    if (!g_client) return;

    const rc_client_user_t *user = rc_client_get_user_info(g_client);
    const rc_client_game_t *game = rc_client_get_game_info(g_client);
    if (!user || !game) return;

    LOGI("Manual unlock: achievement %d", achievementId);

    rc_api_award_achievement_request_t params;
    memset(&params, 0, sizeof(params));
    params.username = user->display_name;
    params.api_token = user->token;
    params.achievement_id = (uint32_t)achievementId;
    params.hardcore = 0;
    params.game_hash = game->hash;

    rc_api_request_t request;
    if (rc_api_init_award_achievement_request(&request, &params) == RC_OK) {
        /* Send via our HTTP mechanism */
        if (g_jvm && g_manager) {
            JNIEnv *jenv = env;
            jstring jUrl = (*jenv)->NewStringUTF(jenv, request.url);
            jstring jPost = request.post_data ? (*jenv)->NewStringUTF(jenv, request.post_data) : NULL;

            QueuedResponse *qr = (QueuedResponse *)calloc(1, sizeof(QueuedResponse));
            qr->callback = NULL;
            qr->callback_data = NULL;

            (*jenv)->CallVoidMethod(jenv, g_manager, g_onServerCall, jUrl, jPost, (jlong)(uintptr_t)qr);
            (*jenv)->DeleteLocalRef(jenv, jUrl);
            if (jPost) (*jenv)->DeleteLocalRef(jenv, jPost);
        }
        rc_api_destroy_request(&request);
    }
}
