#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include "rc_client.h"
#include "rc_consoles.h"

#define LOG_TAG "RA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Bridge functions from libretro_bridge.cpp */
extern void *bridge_get_memory_data(unsigned id);
extern size_t bridge_get_memory_size(unsigned id);

/* ---- state ---- */
static rc_client_t *g_client = NULL;
static JavaVM *g_jvm = NULL;
static jobject g_manager = NULL;
static jmethodID g_onServerCall = NULL;
static jmethodID g_onEvent = NULL;
static jmethodID g_onLoginResult = NULL;
static pthread_mutex_t g_queue_mutex = PTHREAD_MUTEX_INITIALIZER;

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
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.body = head->body;
        response.body_length = head->body_len;
        response.http_status_code = head->http_status;
        head->callback(&response, head->callback_data);
        free(head->body);
        free(head);
        head = next;
    }
}

/* ---- rcheevos callbacks ---- */

static uint32_t ra_read_memory(uint32_t address, uint8_t *buffer, uint32_t num_bytes, rc_client_t *client) {
    (void)client;
    /* Try system RAM first, then save RAM */
    unsigned ids[] = {0 /* RETRO_MEMORY_SAVE_RAM */, 1 /* RETRO_MEMORY_SYSTEM_RAM */};
    /* rcheevos addresses are typically absolute within the console's address space.
       For most cores, system RAM (id=0 in some cores) is what we want.
       We'll use rc_libretro memory mapping for proper support. */
    uint8_t *data = (uint8_t *)bridge_get_memory_data(0); /* RETRO_MEMORY_SAVE_RAM */
    size_t size = bridge_get_memory_size(0);

    if (!data || address + num_bytes > size) {
        data = (uint8_t *)bridge_get_memory_data(1); /* RETRO_MEMORY_SYSTEM_RAM */
        size = bridge_get_memory_size(1);
        if (!data || address + num_bytes > size)
            return 0;
    }

    memcpy(buffer, data + address, num_bytes);
    return num_bytes;
}

static void ra_server_call(const rc_api_request_t *request,
                           rc_client_server_callback_t callback, void *callback_data,
                           rc_client_t *client) {
    (void)client;
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
    rc_client_begin_identify_and_load_game(g_client, (uint32_t)consoleId, path, NULL, 0, ra_load_game_callback, NULL);
    (*env)->ReleaseStringUTFChars(env, romPath, path);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeUnloadGame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_client) rc_client_unload_game(g_client);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeDoFrame(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_client) return;
    process_queued_responses();
    if (rc_client_is_game_loaded(g_client))
        rc_client_do_frame(g_client);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeIdle(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!g_client) return;
    process_queued_responses();
    rc_client_idle(g_client);
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

/* Build a JSON array of achievements */
JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_RetroAchievementsManager_nativeGetAchievements(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return (*env)->NewStringUTF(env, "[]");

    rc_client_achievement_list_t *list = rc_client_create_achievement_list(g_client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (!list) return (*env)->NewStringUTF(env, "[]");

    /* estimate buffer size */
    size_t cap = 4096;
    char *buf = (char *)malloc(cap);
    size_t pos = 0;
    buf[pos++] = '[';

    int first = 1;
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_achievement_bucket_t *bucket = &list->buckets[b];
        for (uint32_t a = 0; a < bucket->num_achievements; a++) {
            const rc_client_achievement_t *ach = bucket->achievements[a];
            int softcore = (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_SOFTCORE) ? 1 : 0;
            int hardcore_only = (!softcore && (ach->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_HARDCORE)) ? 1 : 0;
            if (hardcore_only) continue;

            if (!first) buf[pos++] = ',';
            first = 0;

            /* ensure capacity */
            if (pos + 512 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }

            const char *badge = softcore ?
                (ach->badge_url ? ach->badge_url : "") :
                (ach->badge_locked_url ? ach->badge_locked_url : "");

            pos += snprintf(buf + pos, cap - pos,
                "{\"id\":%u,\"title\":\"%s\",\"description\":\"%s\",\"points\":%u,\"unlocked\":%d,\"state\":%d,\"badge\":\"%s\",\"unlock_time\":%ld}",
                ach->id,
                ach->title ? ach->title : "",
                ach->description ? ach->description : "",
                ach->points,
                softcore,
                ach->state,
                badge,
                (long)ach->unlock_time);
        }
    }
    buf[pos++] = ']';
    buf[pos] = '\0';

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
    (void)env; (void)thiz;
    if (!g_client) return;
    /* Use the award API to submit an unlock */
    /* rc_client doesn't have a direct "manual unlock" — we'll need to use the web API directly.
       For now, just mark it locally via the event system */
    LOGI("Manual unlock requested for achievement %d", achievementId);
}
