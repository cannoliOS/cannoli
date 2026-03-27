#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <vector>
#include <string>
#include <map>

struct VkPassConfig {
    std::vector<uint32_t> vertSpirv;
    std::vector<uint32_t> fragSpirv;
    bool filterLinear = false;
    int scaleType = 0; // 0=source, 1=viewport, 2=absolute
    float scaleX = 1.0f, scaleY = 1.0f;
    bool needsOriginal = false;
};

struct VkPassResources {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkShaderModule vertModule = VK_NULL_HANDLE;
    VkShaderModule fragModule = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
    bool filterLinear = false;
    int scaleType = 0;
    float scaleX = 1.0f, scaleY = 1.0f;
    bool needsOriginal = false;
};

class VulkanRenderer {
public:
    void setCachePath(const std::string &path) { cachePath_ = path; }
    void setLowLatency(bool enabled);
    bool init(ANativeWindow *window);
    void destroy();
    void surfaceChanged(int width, int height);

    bool loadPreset(const std::vector<VkPassConfig> &passes);
    void unloadPreset();

    void setParameter(const std::string &name, float value);
    void setScaling(int mode, float coreAspect, int sharpness);
    void loadOverlay(const uint8_t *pixels, int width, int height);
    void unloadOverlay();
    void renderFrame();

    float getFps() const { return fps_; }
    VkDevice getDevice() const { return device_; }
    int getViewportWidth() const { return vpW_; }
    int getViewportHeight() const { return vpH_; }

private:
    bool createInstance();
    void recreateSwapchain();
    void drawOverlayInline(VkCommandBuffer cmd);
    bool createSurface(ANativeWindow *window);
    bool selectPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    void destroySwapchain();
    bool createRenderPass();
    bool createFrameTexture();
    void updateFrameTexture();
    bool createFullscreenQuad();
    bool createPassthroughPipeline();
    bool createDescriptorPool();
    bool createCommandBuffers();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    bool transitionImageLayout(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout);
    VkShaderModule createShaderModule(const std::vector<uint32_t> &spirv);

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;
    uint32_t graphicsFamily_ = 0;
    uint32_t presentFamily_ = 0;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainViews_;
    std::vector<VkFramebuffer> swapchainFramebuffers_;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_ = {0, 0};

    VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
    std::string cachePath_;

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline passthroughPipeline_ = VK_NULL_HANDLE;
    VkPipeline overlayPipeline_ = VK_NULL_HANDLE;

    VkImage overlayImage_ = VK_NULL_HANDLE;
    VkDeviceMemory overlayMemory_ = VK_NULL_HANDLE;
    VkImageView overlayView_ = VK_NULL_HANDLE;
    VkSampler overlaySampler_ = VK_NULL_HANDLE;
    VkDescriptorSet overlayDescSet_ = VK_NULL_HANDLE;
    bool overlayLoaded_ = false;

    VkBuffer mvpBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory mvpMemory_ = VK_NULL_HANDLE;
    VkBuffer vertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory vertexMemory_ = VK_NULL_HANDLE;

    VkImage frameImage_ = VK_NULL_HANDLE;
    VkDeviceMemory frameMemory_ = VK_NULL_HANDLE;
    VkImageView frameView_ = VK_NULL_HANDLE;
    VkSampler frameSampler_ = VK_NULL_HANDLE;
    VkBuffer stagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory_ = VK_NULL_HANDLE;
    void *stagingMapped_ = nullptr;
    uint32_t frameWidth_ = 0, frameHeight_ = 0;

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;

    std::vector<VkPassResources> passes_;
    VkRenderPass intermediateRenderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout multiPassDescLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout multiPassPipelineLayout_ = VK_NULL_HANDLE;
    bool presetLoaded_ = false;

    bool createIntermediateRenderPass();
    bool createMultiPassLayouts();
    bool createPassPipeline(VkPassResources &pass);
    bool createPassFbo(VkPassResources &pass, uint32_t w, uint32_t h);
    void renderMultiPass();

    int surfaceWidth_ = 0, surfaceHeight_ = 0;
    int scalingMode_ = 0; // 0=core, 1=integer, 2=fullscreen
    float coreAspect_ = 0;
    int sharpness_ = 0;
    int vpX_ = 0, vpY_ = 0, vpW_ = 0, vpH_ = 0;
    float fps_ = 0;
    int frameCount_ = 0;
    uint32_t totalFrames_ = 0;
    uint64_t fpsTimestamp_ = 0;

    bool lowLatency_ = false;
    std::map<std::string, float> params_;
};
