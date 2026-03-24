#include <jni.h>
#include <android/native_window_jni.h>
#include "vulkan_renderer.h"

static VulkanRenderer *g_renderer = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeInit(
    JNIEnv *env, jobject, jobject surface, jstring jcachePath)
{
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    g_renderer = new VulkanRenderer();
    if (jcachePath) {
        const char *cp = env->GetStringUTFChars(jcachePath, nullptr);
        g_renderer->setCachePath(cp);
        env->ReleaseStringUTFChars(jcachePath, cp);
    }
    bool ok = g_renderer->init(window);
    ANativeWindow_release(window);

    if (!ok) {
        delete g_renderer;
        g_renderer = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeRenderFrame(
    JNIEnv *, jobject)
{
    if (g_renderer) g_renderer->renderFrame();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeSurfaceChanged(
    JNIEnv *, jobject, jint width, jint height)
{
    if (g_renderer) g_renderer->surfaceChanged(width, height);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeDestroy(
    JNIEnv *, jobject)
{
    if (g_renderer) {
        g_renderer->destroy();
        delete g_renderer;
        g_renderer = nullptr;
    }
}

JNIEXPORT jfloat JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeGetFps(
    JNIEnv *, jobject)
{
    return g_renderer ? g_renderer->getFps() : 0.0f;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeSetParam(
    JNIEnv *env, jobject, jstring jname, jfloat value)
{
    const char *name = env->GetStringUTFChars(jname, nullptr);
    if (g_renderer) g_renderer->setParameter(name, value);
    env->ReleaseStringUTFChars(jname, name);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeSetScaling(
    JNIEnv *, jobject, jint mode, jfloat coreAspect, jint sharpness)
{
    if (g_renderer) g_renderer->setScaling(mode, coreAspect, sharpness);
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeGetViewportWidth(JNIEnv *, jobject)
{
    return g_renderer ? g_renderer->getViewportWidth() : 0;
}

JNIEXPORT jint JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeGetViewportHeight(JNIEnv *, jobject)
{
    return g_renderer ? g_renderer->getViewportHeight() : 0;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeLoadOverlay(
    JNIEnv *env, jobject, jobject buffer, jint width, jint height)
{
    if (!g_renderer) return;
    auto *pixels = (uint8_t *)env->GetDirectBufferAddress(buffer);
    if (pixels) g_renderer->loadOverlay(pixels, width, height);
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeUnloadOverlay(JNIEnv *, jobject)
{
    if (g_renderer) g_renderer->unloadOverlay();
}

// passData: array of byte arrays [vertSpirv0, fragSpirv0, vertSpirv1, fragSpirv1, ...]
// configData: int array [scaleType0, filterLinear0, needsOriginal0, scaleType1, ...]
// scales: float array [scaleX0, scaleY0, scaleX1, scaleY1, ...]
JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeLoadPreset(
    JNIEnv *env, jobject, jobjectArray passData, jintArray configData, jfloatArray scales, jint passCount)
{
    if (!g_renderer || passCount <= 0) return JNI_FALSE;

    jint *config = env->GetIntArrayElements(configData, nullptr);
    jfloat *sc = env->GetFloatArrayElements(scales, nullptr);

    std::vector<VkPassConfig> passes(passCount);
    for (int i = 0; i < passCount; i++) {
        auto vertArr = (jbyteArray)env->GetObjectArrayElement(passData, i * 2);
        auto fragArr = (jbyteArray)env->GetObjectArrayElement(passData, i * 2 + 1);

        jsize vertLen = env->GetArrayLength(vertArr);
        jsize fragLen = env->GetArrayLength(fragArr);
        jbyte *vertBytes = env->GetByteArrayElements(vertArr, nullptr);
        jbyte *fragBytes = env->GetByteArrayElements(fragArr, nullptr);

        passes[i].vertSpirv.assign((uint32_t*)vertBytes, (uint32_t*)(vertBytes + vertLen));
        passes[i].fragSpirv.assign((uint32_t*)fragBytes, (uint32_t*)(fragBytes + fragLen));

        env->ReleaseByteArrayElements(vertArr, vertBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(fragArr, fragBytes, JNI_ABORT);

        passes[i].scaleType = config[i * 3];
        passes[i].filterLinear = config[i * 3 + 1] != 0;
        passes[i].needsOriginal = config[i * 3 + 2] != 0;
        passes[i].scaleX = sc[i * 2];
        passes[i].scaleY = sc[i * 2 + 1];
    }

    env->ReleaseIntArrayElements(configData, config, JNI_ABORT);
    env->ReleaseFloatArrayElements(scales, sc, JNI_ABORT);

    return g_renderer->loadPreset(passes) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeUnloadPreset(JNIEnv *, jobject)
{
    if (g_renderer) g_renderer->unloadPreset();
}

JNIEXPORT void JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeWaitIdle(JNIEnv *, jobject)
{
    if (g_renderer) vkDeviceWaitIdle(g_renderer->getDevice());
}

}
