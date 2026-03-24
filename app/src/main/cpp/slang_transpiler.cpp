#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <SPIRV/GlslangToSpv.h>
#include <spirv_glsl.hpp>

#define TAG "SlangTranspiler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_initialized = false;
static std::string g_last_error;

static void ensureInit() {
    if (!g_initialized) {
        glslang::InitializeProcess();
        g_initialized = true;
    }
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_shader_SlangTranspiler_nativeTranspile(
    JNIEnv *env, jobject, jstring jsource, jboolean isVertex)
{
    ensureInit();
    const char *src = env->GetStringUTFChars(jsource, nullptr);
    std::string source(src);
    env->ReleaseStringUTFChars(jsource, src);

    EShLanguage stage = isVertex ? EShLangVertex : EShLangFragment;
    glslang::TShader shader(stage);
    const char *sources[] = { source.c_str() };
    shader.setStrings(sources, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);

    const TBuiltInResource *resources = GetDefaultResources();
    if (!shader.parse(resources, 100, false, EShMsgVulkanRules)) {
        g_last_error = shader.getInfoLog();
        LOGE("Parse error: %s", g_last_error.c_str());
        return nullptr;
    }

    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(EShMsgVulkanRules)) {
        g_last_error = program.getInfoLog();
        LOGE("Link error: %s", g_last_error.c_str());
        return nullptr;
    }

    std::vector<uint32_t> spirv;
    glslang::GlslangToSpv(*program.getIntermediate(stage), spirv);
    if (spirv.empty()) {
        g_last_error = "SPIR-V generation produced empty output";
        LOGE("%s", g_last_error.c_str());
        return nullptr;
    }

    spirv_cross::CompilerGLSL compiler(std::move(spirv));
    spirv_cross::CompilerGLSL::Options opts;
    opts.version = 300;
    opts.es = true;
    opts.vulkan_semantics = false;
    opts.emit_push_constant_as_uniform_buffer = false;
    compiler.set_common_options(opts);

    std::string result;
    try {
        result = compiler.compile();
    } catch (const spirv_cross::CompilerError &e) {
        g_last_error = e.what();
        LOGE("spirv-cross error: %s", g_last_error.c_str());
        return nullptr;
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_dev_cannoli_scorza_libretro_shader_SlangTranspiler_nativeGetLastError(
    JNIEnv *env, jobject)
{
    return env->NewStringUTF(g_last_error.c_str());
}

}
