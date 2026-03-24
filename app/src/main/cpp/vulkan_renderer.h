#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <vector>
#include <string>
#include <map>

struct VkPassResources {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
};

class VulkanRenderer {
public:
    bool init(ANativeWindow *window);
    void destroy();
    void surfaceChanged(int width, int height);

    bool loadPreset(const std::vector<std::vector<uint32_t>> &spirvModules,
                    const std::vector<bool> &filterLinear);
    void unloadPreset();

    void setParameter(const std::string &name, float value);
    void renderFrame();

    float getFps() const { return fps_; }

private:
    bool createInstance();
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

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline passthroughPipeline_ = VK_NULL_HANDLE;

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

    int surfaceWidth_ = 0, surfaceHeight_ = 0;
    float fps_ = 0;
    int frameCount_ = 0;
    uint64_t fpsTimestamp_ = 0;

    std::map<std::string, float> params_;
};
