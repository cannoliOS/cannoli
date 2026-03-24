#include <jni.h>
#include <android/native_window_jni.h>
#include "vulkan_renderer.h"

static VulkanRenderer *g_renderer = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_cannoli_scorza_libretro_VulkanBackend_nativeInit(
    JNIEnv *env, jobject, jobject surface)
{
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    g_renderer = new VulkanRenderer();
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

}
