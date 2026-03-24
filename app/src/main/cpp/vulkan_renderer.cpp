#include "vulkan_renderer.h"
#include "frame_buffer.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <ctime>
#include <array>

typedef FrameBuffer* (*GetFrameBufferFn)();
typedef void (*MarkFrameConsumedFn)();

static GetFrameBufferFn pfnGetFrameBuffer = nullptr;
static MarkFrameConsumedFn pfnMarkFrameConsumed = nullptr;

static bool resolveFrameBufferFns() {
    if (pfnGetFrameBuffer) return true;
    void *handle = dlopen("libretro_bridge.so", RTLD_NOW);
    if (!handle) return false;
    pfnGetFrameBuffer = (GetFrameBufferFn)dlsym(handle, "getFrameBuffer");
    pfnMarkFrameConsumed = (MarkFrameConsumedFn)dlsym(handle, "markFrameConsumed");
    return pfnGetFrameBuffer && pfnMarkFrameConsumed;
}

#define TAG "VulkanRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Embedded passthrough SPIR-V shaders (compiled offline)
// Vertex: fullscreen triangle, TexCoord passed through
// Fragment: samples texture at TexCoord
#include "passthrough_vert.h"
#include "passthrough_frag.h"

static uint64_t nowNanos() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + ts.tv_nsec;
}

bool VulkanRenderer::init(ANativeWindow *window) {
    if (!resolveFrameBufferFns()) return false;
    if (!createInstance()) return false;
    if (!createSurface(window)) return false;
    if (!selectPhysicalDevice()) return false;
    if (!createDevice()) return false;
    if (!createSwapchain()) return false;
    if (!createRenderPass()) return false;

    // Pipeline cache — load from disk if available
    VkPipelineCacheCreateInfo cacheInfo{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
    if (!cachePath_.empty()) {
        FILE *f = fopen(cachePath_.c_str(), "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            size_t sz = ftell(f);
            fseek(f, 0, SEEK_SET);
            std::vector<uint8_t> data(sz);
            fread(data.data(), 1, sz, f);
            fclose(f);
            cacheInfo.initialDataSize = sz;
            cacheInfo.pInitialData = data.data();
            LOGI("Loaded pipeline cache: %zu bytes", sz);
        }
    }
    vkCreatePipelineCache(device_, &cacheInfo, nullptr, &pipelineCache_);

    // Swapchain framebuffers
    swapchainFramebuffers_.resize(swapchainViews_.size());
    for (size_t i = 0; i < swapchainViews_.size(); i++) {
        VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbInfo.renderPass = renderPass_;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &swapchainViews_[i];
        fbInfo.width = swapchainExtent_.width;
        fbInfo.height = swapchainExtent_.height;
        fbInfo.layers = 1;
        if (vkCreateFramebuffer(device_, &fbInfo, nullptr, &swapchainFramebuffers_[i]) != VK_SUCCESS) {
            LOGE("Failed to create swapchain framebuffer");
            return false;
        }
    }

    if (!createDescriptorPool()) return false;
    if (!createFullscreenQuad()) return false;
    if (!createPassthroughPipeline()) return false;
    if (!createCommandBuffers()) return false;

    // Sync objects
    VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    vkCreateSemaphore(device_, &semInfo, nullptr, &imageAvailableSemaphore_);
    vkCreateSemaphore(device_, &semInfo, nullptr, &renderFinishedSemaphore_);
    vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_);

    fpsTimestamp_ = nowNanos();
    LOGI("Vulkan renderer initialized");
    return true;
}

bool VulkanRenderer::createInstance() {
    VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "Cannoli";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "LowFatRicotta";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    const char *extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.ppEnabledExtensionNames = extensions;

    if (vkCreateInstance(&createInfo, nullptr, &instance_) != VK_SUCCESS) {
        LOGE("Failed to create Vulkan instance");
        return false;
    }
    return true;
}

bool VulkanRenderer::createSurface(ANativeWindow *window) {
    VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.window = window;
    if (vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_) != VK_SUCCESS) {
        LOGE("Failed to create Android surface");
        return false;
    }
    return true;
}

bool VulkanRenderer::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) { LOGE("No Vulkan devices found"); return false; }

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    for (auto &dev : devices) {
        uint32_t queueCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueCount, families.data());

        int gfxIdx = -1, presentIdx = -1;
        for (uint32_t i = 0; i < queueCount; i++) {
            if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) gfxIdx = i;
            VkBool32 presentSupport = false;
            vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface_, &presentSupport);
            if (presentSupport) presentIdx = i;
        }

        if (gfxIdx >= 0 && presentIdx >= 0) {
            physicalDevice_ = dev;
            graphicsFamily_ = gfxIdx;
            presentFamily_ = presentIdx;
            VkPhysicalDeviceProperties props;
            vkGetPhysicalDeviceProperties(dev, &props);
            LOGI("Selected GPU: %s", props.deviceName);
            return true;
        }
    }
    LOGE("No suitable GPU found");
    return false;
}

bool VulkanRenderer::createDevice() {
    float priority = 1.0f;
    std::vector<VkDeviceQueueCreateInfo> queueInfos;

    VkDeviceQueueCreateInfo qInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qInfo.queueFamilyIndex = graphicsFamily_;
    qInfo.queueCount = 1;
    qInfo.pQueuePriorities = &priority;
    queueInfos.push_back(qInfo);

    if (presentFamily_ != graphicsFamily_) {
        VkDeviceQueueCreateInfo pInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        pInfo.queueFamilyIndex = presentFamily_;
        pInfo.queueCount = 1;
        pInfo.pQueuePriorities = &priority;
        queueInfos.push_back(pInfo);
    }

    const char *deviceExtensions[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = (uint32_t)queueInfos.size();
    deviceInfo.pQueueCreateInfos = queueInfos.data();
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = deviceExtensions;

    if (vkCreateDevice(physicalDevice_, &deviceInfo, nullptr, &device_) != VK_SUCCESS) {
        LOGE("Failed to create logical device");
        return false;
    }

    vkGetDeviceQueue(device_, graphicsFamily_, 0, &graphicsQueue_);
    vkGetDeviceQueue(device_, presentFamily_, 0, &presentQueue_);

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.queueFamilyIndex = graphicsFamily_;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        LOGE("Failed to create command pool");
        return false;
    }

    return true;
}

bool VulkanRenderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps);

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());

    swapchainFormat_ = formats[0].format;
    VkColorSpaceKHR colorSpace = formats[0].colorSpace;
    for (auto &f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
            swapchainFormat_ = f.format;
            colorSpace = f.colorSpace;
            break;
        }
    }

    swapchainExtent_ = caps.currentExtent;
    if (swapchainExtent_.width == UINT32_MAX) {
        swapchainExtent_.width = surfaceWidth_;
        swapchainExtent_.height = surfaceHeight_;
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount)
        imageCount = caps.maxImageCount;

    VkSwapchainCreateInfoKHR swapInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    swapInfo.surface = surface_;
    swapInfo.minImageCount = imageCount;
    swapInfo.imageFormat = swapchainFormat_;
    swapInfo.imageColorSpace = colorSpace;
    swapInfo.imageExtent = swapchainExtent_;
    swapInfo.imageArrayLayers = 1;
    swapInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapInfo.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    // Pick a supported compositeAlpha
    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    VkCompositeAlphaFlagBitsKHR preferred[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
    };
    for (auto a : preferred) {
        if (caps.supportedCompositeAlpha & a) { compositeAlpha = a; break; }
    }
    swapInfo.compositeAlpha = compositeAlpha;
    swapInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapInfo.clipped = VK_TRUE;

    uint32_t familyIndices[] = { graphicsFamily_, presentFamily_ };
    if (graphicsFamily_ != presentFamily_) {
        swapInfo.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        swapInfo.queueFamilyIndexCount = 2;
        swapInfo.pQueueFamilyIndices = familyIndices;
    } else {
        swapInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    }

    if (vkCreateSwapchainKHR(device_, &swapInfo, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("Failed to create swapchain");
        return false;
    }

    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    swapchainImages_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapchainImages_.data());

    swapchainViews_.resize(imageCount);
    for (uint32_t i = 0; i < imageCount; i++) {
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = swapchainImages_[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainFormat_;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCreateImageView(device_, &viewInfo, nullptr, &swapchainViews_[i]);
    }

    LOGI("Swapchain created: %dx%d, %d images", swapchainExtent_.width, swapchainExtent_.height, imageCount);
    return true;
}

bool VulkanRenderer::createRenderPass() {
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = swapchainFormat_;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &colorAttachment;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &subpass;
    rpInfo.dependencyCount = 1;
    rpInfo.pDependencies = &dep;

    if (vkCreateRenderPass(device_, &rpInfo, nullptr, &renderPass_) != VK_SUCCESS) {
        LOGE("Failed to create render pass");
        return false;
    }
    return true;
}

bool VulkanRenderer::createFrameTexture() {
    FrameBuffer *fb = pfnGetFrameBuffer();
    if (!fb || fb->width == 0 || fb->height == 0) return false;
    frameWidth_ = fb->width;
    frameHeight_ = fb->height;

    // Create image
    VkImageCreateInfo imgInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {frameWidth_, frameHeight_, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    vkCreateImage(device_, &imgInfo, nullptr, &frameImage_);

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, frameImage_, &memReq);
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(device_, &allocInfo, nullptr, &frameMemory_);
    vkBindImageMemory(device_, frameImage_, frameMemory_, 0);

    // Image view
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = frameImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCreateImageView(device_, &viewInfo, nullptr, &frameView_);

    // Sampler
    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(device_, &samplerInfo, nullptr, &frameSampler_);

    // Staging buffer
    size_t bufSize = frameWidth_ * frameHeight_ * 4;
    VkBufferCreateInfo bufInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufInfo.size = bufSize;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    vkCreateBuffer(device_, &bufInfo, nullptr, &stagingBuffer_);

    VkMemoryRequirements bufMemReq;
    vkGetBufferMemoryRequirements(device_, stagingBuffer_, &bufMemReq);
    VkMemoryAllocateInfo bufAllocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    bufAllocInfo.allocationSize = bufMemReq.size;
    bufAllocInfo.memoryTypeIndex = findMemoryType(bufMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device_, &bufAllocInfo, nullptr, &stagingMemory_);
    vkBindBufferMemory(device_, stagingBuffer_, stagingMemory_, 0);
    vkMapMemory(device_, stagingMemory_, 0, bufSize, 0, &stagingMapped_);

    // Transition to transfer dst
    transitionImageLayout(frameImage_, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

    // Update descriptor set
    VkDescriptorImageInfo descImgInfo{};
    descImgInfo.sampler = frameSampler_;
    descImgInfo.imageView = frameView_;
    descImgInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = descriptorSet_;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &descImgInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

    return true;
}

void VulkanRenderer::updateFrameTexture() {
    FrameBuffer *fb = pfnGetFrameBuffer();
    if (!fb || !fb->ready) return;

    if (fb->width != frameWidth_ || fb->height != frameHeight_ || frameImage_ == VK_NULL_HANDLE) {
        // Recreate if size changed
        if (frameImage_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
            vkDestroyImageView(device_, frameView_, nullptr);
            vkDestroyImage(device_, frameImage_, nullptr);
            vkFreeMemory(device_, frameMemory_, nullptr);
            vkDestroySampler(device_, frameSampler_, nullptr);
            vkDestroyBuffer(device_, stagingBuffer_, nullptr);
            vkFreeMemory(device_, stagingMemory_, nullptr);
            frameImage_ = VK_NULL_HANDLE;
        }
        createFrameTexture();
    }

    // Copy frame data to staging buffer with Y-flip (match GLES convention)
    size_t rowBytes = fb->width * 4;
    uint8_t *dst = (uint8_t *)stagingMapped_;
    unsigned h = fb->height;

    if (fb->pixel_format == 1) { // XRGB8888
        for (unsigned y = 0; y < h; y++) {
            memcpy(dst + (h - 1 - y) * rowBytes, fb->data + y * fb->pitch, rowBytes);
        }
    } else { // RGB565 - expand to RGBA
        for (unsigned y = 0; y < h; y++) {
            const uint16_t *src16 = (const uint16_t *)(fb->data + y * fb->pitch);
            uint32_t *dst32 = (uint32_t *)(dst + (h - 1 - y) * rowBytes);
            for (unsigned x = 0; x < fb->width; x++) {
                uint16_t px = src16[x];
                uint32_t r = (px >> 11) & 0x1F;
                uint32_t g = (px >> 5) & 0x3F;
                uint32_t b = px & 0x1F;
                dst32[x] = 0xFF000000 | ((b << 3) << 16) | ((g << 2) << 8) | (r << 3);
            }
        }
    }

    pfnMarkFrameConsumed();

    // Copy staging → image
    VkCommandBuffer cmd = commandBuffers_[0];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    // Transition to transfer dst
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.image = frameImage_;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier);

    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {frameWidth_, frameHeight_, 1};
    vkCmdCopyBufferToImage(cmd, stagingBuffer_, frameImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // Transition to shader read
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(cmd);
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(graphicsQueue_);
}

void VulkanRenderer::renderFrame() {
    if (swapchain_ == VK_NULL_HANDLE || swapchainFramebuffers_.empty()) return;

    FrameBuffer *fb = pfnGetFrameBuffer();
    if (!fb || fb->width == 0) return;

    vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &inFlightFence_);

    updateFrameTexture();
    if (frameImage_ == VK_NULL_HANDLE) return;

    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        recreateSwapchain();
        return;
    }
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) return;

    // Compute viewport
    int sw = swapchainExtent_.width, sh = swapchainExtent_.height;
    float gameAspect = (scalingMode_ == 2) ? (float)sw / sh
        : (coreAspect_ > 0) ? coreAspect_ : (float)frameWidth_ / frameHeight_;
    float screenAspect = (float)sw / sh;

    if (scalingMode_ == 1) {
        int scale = std::max(1, std::min((int)(sw / frameWidth_), (int)(sh / frameHeight_)));
        vpW_ = frameWidth_ * scale;
        vpH_ = frameHeight_ * scale;
    } else if (gameAspect > screenAspect) {
        vpW_ = sw;
        vpH_ = (int)(sw / gameAspect);
    } else {
        vpW_ = (int)(sh * gameAspect);
        vpH_ = sh;
    }
    vpX_ = (sw - vpW_) / 2;
    vpY_ = (sh - vpH_) / 2;

    VkCommandBuffer cmd = commandBuffers_[0];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(cmd, &beginInfo);

    if (presetLoaded_ && !passes_.empty()) {
        renderMultiPass();
        // Last pass: render to swapchain
        auto &lastPass = passes_.back();

        // Build push constants
        struct { float s[4]; float o[4]; float out[4]; uint32_t fc; float p[9]; } push{};
        push.o[0] = (float)frameWidth_; push.o[1] = (float)frameHeight_;
        push.o[2] = 1.0f / frameWidth_; push.o[3] = 1.0f / frameHeight_;
        push.out[0] = (float)vpW_; push.out[1] = (float)vpH_;
        push.out[2] = 1.0f / vpW_; push.out[3] = 1.0f / vpH_;
        if (passes_.size() > 1) {
            auto &prev = passes_[passes_.size()-2];
            push.s[0] = (float)prev.width; push.s[1] = (float)prev.height;
        } else {
            push.s[0] = (float)frameWidth_; push.s[1] = (float)frameHeight_;
        }
        push.s[2] = 1.0f / push.s[0]; push.s[3] = 1.0f / push.s[1];
        push.fc = totalFrames_;
        const char *pn[] = {"curvature","scanline","mask_dark","vignette","glow_strength","sweep","sweep_bright","crt_brightness","noise_amount"};
        float pd[] = {1.7f,0.75f,0.3f,0.85f,0.25f,1.0f,0.35f,1.0f,0.15f};
        for (int i = 0; i < 9; i++) { auto it = params_.find(pn[i]); push.p[i] = (it != params_.end()) ? it->second : pd[i]; }

        // Update last pass descriptors: 0=UBO, 2=Source, 3=Original
        VkDescriptorBufferInfo uboInfo{mvpBuffer_, 0, 64};
        VkDescriptorImageInfo srcInfo{frameSampler_, (passes_.size() > 1) ? passes_[passes_.size()-2].view : frameView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorImageInfo origInfo{frameSampler_, frameView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet writes[3]{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = lastPass.descriptorSet;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[0].pBufferInfo = &uboInfo;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = lastPass.descriptorSet;
        writes[1].dstBinding = 2;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[1].pImageInfo = &srcInfo;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = lastPass.descriptorSet;
        writes[2].dstBinding = 3;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[2].pImageInfo = &origInfo;
        vkUpdateDescriptorSets(device_, 3, writes, 0, nullptr);

        VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
        VkRenderPassBeginInfo rpBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rpBegin.renderPass = renderPass_;
        rpBegin.framebuffer = swapchainFramebuffers_[imageIndex];
        rpBegin.renderArea.extent = swapchainExtent_;
        rpBegin.clearValueCount = 1;
        rpBegin.pClearValues = &clear;

        vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, lastPass.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, multiPassPipelineLayout_, 0, 1, &lastPass.descriptorSet, 0, nullptr);
        vkCmdPushConstants(cmd, multiPassPipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);

        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer_, &offset);
        VkViewport viewport{(float)vpX_, (float)vpY_, (float)vpW_, (float)vpH_, 0.0f, 1.0f};
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        VkRect2D scissor{{vpX_, vpY_}, {(uint32_t)vpW_, (uint32_t)vpH_}};
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        drawOverlayInline(cmd);
        vkCmdEndRenderPass(cmd);
    } else {
        // Passthrough
        VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
        VkRenderPassBeginInfo rpBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rpBegin.renderPass = renderPass_;
        rpBegin.framebuffer = swapchainFramebuffers_[imageIndex];
        rpBegin.renderArea.extent = swapchainExtent_;
        rpBegin.clearValueCount = 1;
        rpBegin.pClearValues = &clearColor;

        vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, passthroughPipeline_);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 1, &descriptorSet_, 0, nullptr);

        VkViewport viewport{(float)vpX_, (float)vpY_, (float)vpW_, (float)vpH_, 0.0f, 1.0f};
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        VkRect2D scissor{{vpX_, vpY_}, {(uint32_t)vpW_, (uint32_t)vpH_}};
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        drawOverlayInline(cmd);
        vkCmdEndRenderPass(cmd);
    }


    vkEndCommandBuffer(cmd);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &imageAvailableSemaphore_;
    submitInfo.pWaitDstStageMask = &waitStage;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &renderFinishedSemaphore_;
    vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_);

    VkPresentInfoKHR presentInfo{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = &renderFinishedSemaphore_;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &swapchain_;
    presentInfo.pImageIndices = &imageIndex;
    vkQueuePresentKHR(presentQueue_, &presentInfo);

    // FPS tracking
    frameCount_++;
    totalFrames_++;
    uint64_t now = nowNanos();
    uint64_t elapsed = now - fpsTimestamp_;
    if (elapsed >= 1000000000ULL) {
        fps_ = frameCount_ * 1000000000.0f / elapsed;
        frameCount_ = 0;
        fpsTimestamp_ = now;
    }
}

bool VulkanRenderer::createDescriptorPool() {
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_);

    VkDescriptorPoolSize poolSizes[] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 32},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 16}
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 32;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_);

    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    vkAllocateDescriptorSets(device_, &allocInfo, &descriptorSet_);

    return true;
}

bool VulkanRenderer::createFullscreenQuad() {
    // Fullscreen triangle — no vertex buffer needed, generated in vertex shader
    return true;
}

bool VulkanRenderer::createPassthroughPipeline() {
    VkShaderModule vertModule = createShaderModule(
        std::vector<uint32_t>(passthrough_vert_spv, passthrough_vert_spv + sizeof(passthrough_vert_spv)/4));
    VkShaderModule fragModule = createShaderModule(
        std::vector<uint32_t>(passthrough_frag_spv, passthrough_frag_spv + sizeof(passthrough_frag_spv)/4));

    if (vertModule == VK_NULL_HANDLE || fragModule == VK_NULL_HANDLE) {
        LOGE("Failed to create passthrough shader modules");
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragModule;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkDynamicState dynStates[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
    VkPipelineDynamicStateCreateInfo dynamicState{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynStates;

    VkPipelineRasterizationStateCreateInfo rasterizer{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;

    VkPipelineMultisampleStateCreateInfo multisampling{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo colorBlend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &blendAttachment;

    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_);

    VkGraphicsPipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pColorBlendState = &colorBlend;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = pipelineLayout_;
    pipelineInfo.renderPass = renderPass_;

    VkResult res = vkCreateGraphicsPipelines(device_, pipelineCache_, 1, &pipelineInfo, nullptr, &passthroughPipeline_);
    vkDestroyShaderModule(device_, vertModule, nullptr);
    vkDestroyShaderModule(device_, fragModule, nullptr);

    if (res != VK_SUCCESS) { LOGE("Failed to create passthrough pipeline"); return false; }
    return true;
}

bool VulkanRenderer::createCommandBuffers() {
    commandBuffers_.resize(1);
    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    return vkAllocateCommandBuffers(device_, &allocInfo, commandBuffers_.data()) == VK_SUCCESS;
}

uint32_t VulkanRenderer::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProps.memoryTypes[i].propertyFlags & properties) == properties)
            return i;
    }
    return 0;
}

bool VulkanRenderer::transitionImageLayout(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkCommandBuffer cmd;
    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    vkAllocateCommandBuffers(device_, &allocInfo, &cmd);

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(cmd);
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(graphicsQueue_);
    vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
    return true;
}

VkShaderModule VulkanRenderer::createShaderModule(const std::vector<uint32_t> &spirv) {
    VkShaderModuleCreateInfo createInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    createInfo.codeSize = spirv.size() * 4;
    createInfo.pCode = spirv.data();
    VkShaderModule module;
    if (vkCreateShaderModule(device_, &createInfo, nullptr, &module) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return module;
}

void VulkanRenderer::surfaceChanged(int width, int height) {
    surfaceWidth_ = width;
    surfaceHeight_ = height;
}

void VulkanRenderer::recreateSwapchain() {
    if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) return;
    if (surfaceWidth_ <= 0 || surfaceHeight_ <= 0) return;
    vkDeviceWaitIdle(device_);

    for (auto fb : swapchainFramebuffers_) vkDestroyFramebuffer(device_, fb, nullptr);
    for (auto iv : swapchainViews_) vkDestroyImageView(device_, iv, nullptr);
    swapchainFramebuffers_.clear();
    swapchainViews_.clear();

    VkSwapchainKHR oldSwapchain = swapchain_;
    if (!createSwapchain()) {
        LOGE("Failed to recreate swapchain");
        return;
    }
    if (oldSwapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, oldSwapchain, nullptr);

    swapchainFramebuffers_.resize(swapchainViews_.size());
    for (size_t i = 0; i < swapchainViews_.size(); i++) {
        VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbInfo.renderPass = renderPass_;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &swapchainViews_[i];
        fbInfo.width = swapchainExtent_.width;
        fbInfo.height = swapchainExtent_.height;
        fbInfo.layers = 1;
        vkCreateFramebuffer(device_, &fbInfo, nullptr, &swapchainFramebuffers_[i]);
    }
}

void VulkanRenderer::setParameter(const std::string &name, float value) {
    params_[name] = value;
}

void VulkanRenderer::drawOverlayInline(VkCommandBuffer cmd) {
    if (!overlayLoaded_ || !overlayPipeline_) return;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, overlayPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 1, &overlayDescSet_, 0, nullptr);
    VkViewport vp{0, 0, (float)swapchainExtent_.width, (float)swapchainExtent_.height, 0, 1};
    vkCmdSetViewport(cmd, 0, 1, &vp);
    VkRect2D sc{{0, 0}, swapchainExtent_};
    vkCmdSetScissor(cmd, 0, 1, &sc);
    vkCmdDraw(cmd, 3, 1, 0, 0);
}

void VulkanRenderer::setScaling(int mode, float coreAspect, int sharpness) {
    scalingMode_ = mode;
    coreAspect_ = coreAspect;
    sharpness_ = sharpness;
}

void VulkanRenderer::loadOverlay(const uint8_t *pixels, int width, int height) {
    unloadOverlay();

    // Create overlay render pass (LOAD existing content, draw on top)
    if (overlayRenderPass_ == VK_NULL_HANDLE) {
        VkAttachmentDescription att{};
        att.format = swapchainFormat_;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;

        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo rpInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rpInfo.attachmentCount = 1;
        rpInfo.pAttachments = &att;
        rpInfo.subpassCount = 1;
        rpInfo.pSubpasses = &sub;
        rpInfo.dependencyCount = 1;
        rpInfo.pDependencies = &dep;
        vkCreateRenderPass(device_, &rpInfo, nullptr, &overlayRenderPass_);
    }

    // Create overlay pipeline with alpha blending
    if (overlayPipeline_ == VK_NULL_HANDLE) {
        VkShaderModule vertModule = createShaderModule(
            std::vector<uint32_t>(passthrough_vert_spv, passthrough_vert_spv + sizeof(passthrough_vert_spv)/4));
        VkShaderModule fragModule = createShaderModule(
            std::vector<uint32_t>(passthrough_frag_spv, passthrough_frag_spv + sizeof(passthrough_frag_spv)/4));

        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vertModule;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragModule;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo inputAssembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkDynamicState dynStates[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
        VkPipelineDynamicStateCreateInfo dynamicState{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dynamicState.dynamicStateCount = 2;
        dynamicState.pDynamicStates = dynStates;

        VkPipelineRasterizationStateCreateInfo rasterizer{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.lineWidth = 1.0f;
        rasterizer.cullMode = VK_CULL_MODE_NONE;

        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAtt{};
        blendAtt.blendEnable = VK_TRUE;
        blendAtt.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blendAtt.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAtt.colorBlendOp = VK_BLEND_OP_ADD;
        blendAtt.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blendAtt.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blendAtt.alphaBlendOp = VK_BLEND_OP_ADD;
        blendAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo colorBlend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        colorBlend.attachmentCount = 1;
        colorBlend.pAttachments = &blendAtt;

        VkGraphicsPipelineCreateInfo pInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pInfo.stageCount = 2;
        pInfo.pStages = stages;
        pInfo.pVertexInputState = &vertexInput;
        pInfo.pInputAssemblyState = &inputAssembly;
        pInfo.pViewportState = &viewportState;
        pInfo.pRasterizationState = &rasterizer;
        pInfo.pMultisampleState = &ms;
        pInfo.pColorBlendState = &colorBlend;
        pInfo.pDynamicState = &dynamicState;
        pInfo.layout = pipelineLayout_;
        pInfo.renderPass = renderPass_;
        vkCreateGraphicsPipelines(device_, pipelineCache_, 1, &pInfo, nullptr, &overlayPipeline_);

        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
    }

    // Create overlay image
    VkImageCreateInfo imgInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {(uint32_t)width, (uint32_t)height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    vkCreateImage(device_, &imgInfo, nullptr, &overlayImage_);

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, overlayImage_, &memReq);
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(device_, &allocInfo, nullptr, &overlayMemory_);
    vkBindImageMemory(device_, overlayImage_, overlayMemory_, 0);

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = overlayImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCreateImageView(device_, &viewInfo, nullptr, &overlayView_);

    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(device_, &samplerInfo, nullptr, &overlaySampler_);

    // Upload via staging buffer (reuse frame staging if big enough, else temp)
    size_t dataSize = width * height * 4;
    VkBuffer stageBuf;
    VkDeviceMemory stageMem;
    VkBufferCreateInfo bufInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufInfo.size = dataSize;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    vkCreateBuffer(device_, &bufInfo, nullptr, &stageBuf);
    VkMemoryRequirements bufReq;
    vkGetBufferMemoryRequirements(device_, stageBuf, &bufReq);
    VkMemoryAllocateInfo bufAlloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    bufAlloc.allocationSize = bufReq.size;
    bufAlloc.memoryTypeIndex = findMemoryType(bufReq.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device_, &bufAlloc, nullptr, &stageMem);
    vkBindBufferMemory(device_, stageBuf, stageMem, 0);

    void *mapped;
    vkMapMemory(device_, stageMem, 0, dataSize, 0, &mapped);
    memcpy(mapped, pixels, dataSize);
    vkUnmapMemory(device_, stageMem);

    // Copy to image
    VkCommandBuffer cmd;
    VkCommandBufferAllocateInfo cmdAlloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cmdAlloc.commandPool = commandPool_;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(device_, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);

    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.image = overlayImage_;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {(uint32_t)width, (uint32_t)height, 1};
    vkCmdCopyBufferToImage(cmd, stageBuf, overlayImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(cmd);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vkQueueSubmit(graphicsQueue_, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(graphicsQueue_);
    vkFreeCommandBuffers(device_, commandPool_, 1, &cmd);
    vkDestroyBuffer(device_, stageBuf, nullptr);
    vkFreeMemory(device_, stageMem, nullptr);

    // Allocate descriptor set for overlay
    VkDescriptorSetAllocateInfo dsAlloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsAlloc.descriptorPool = descriptorPool_;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts = &descriptorSetLayout_;
    vkAllocateDescriptorSets(device_, &dsAlloc, &overlayDescSet_);

    VkDescriptorImageInfo descInfo{overlaySampler_, overlayView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = overlayDescSet_;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &descInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

    overlayLoaded_ = true;
    LOGI("Overlay loaded: %dx%d", width, height);
}

void VulkanRenderer::unloadOverlay() {
    if (!overlayLoaded_) return;
    vkDeviceWaitIdle(device_);
    if (overlayView_) vkDestroyImageView(device_, overlayView_, nullptr);
    if (overlayImage_) vkDestroyImage(device_, overlayImage_, nullptr);
    if (overlayMemory_) vkFreeMemory(device_, overlayMemory_, nullptr);
    if (overlaySampler_) vkDestroySampler(device_, overlaySampler_, nullptr);
    overlayImage_ = VK_NULL_HANDLE;
    overlayView_ = VK_NULL_HANDLE;
    overlayMemory_ = VK_NULL_HANDLE;
    overlaySampler_ = VK_NULL_HANDLE;
    overlayLoaded_ = false;
}

bool VulkanRenderer::createIntermediateRenderPass() {
    VkAttachmentDescription att{};
    att.format = VK_FORMAT_R8G8B8A8_UNORM;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;

    VkRenderPassCreateInfo rpInfo{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &att;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &sub;
    return vkCreateRenderPass(device_, &rpInfo, nullptr, &intermediateRenderPass_) == VK_SUCCESS;
}

bool VulkanRenderer::createMultiPassLayouts() {
    // Match shader bindings: 0=UBO(MVP), 2=Source sampler, 3=Original sampler
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
    bindings[1].binding = 2;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    bindings[2].binding = 3;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &multiPassDescLayout_) != VK_SUCCESS)
        return false;

    // Create MVP UBO (identity matrix, never changes)
    float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    VkBufferCreateInfo uboBufInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    uboBufInfo.size = sizeof(identity);
    uboBufInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    vkCreateBuffer(device_, &uboBufInfo, nullptr, &mvpBuffer_);
    VkMemoryRequirements uboReq;
    vkGetBufferMemoryRequirements(device_, mvpBuffer_, &uboReq);
    VkMemoryAllocateInfo uboAlloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    uboAlloc.allocationSize = uboReq.size;
    uboAlloc.memoryTypeIndex = findMemoryType(uboReq.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device_, &uboAlloc, nullptr, &mvpMemory_);
    vkBindBufferMemory(device_, mvpBuffer_, mvpMemory_, 0);
    void *mapped;
    vkMapMemory(device_, mvpMemory_, 0, sizeof(identity), 0, &mapped);
    memcpy(mapped, identity, sizeof(identity));
    vkUnmapMemory(device_, mvpMemory_);

    // Fullscreen triangle vertex buffer: Position(vec4) + TexCoord(vec2) interleaved
    struct Vertex { float pos[4]; float tc[2]; };
    Vertex verts[3] = {
        {{-1, -1, 0, 1}, {0, 0}},
        {{ 3, -1, 0, 1}, {2, 0}},
        {{-1,  3, 0, 1}, {0, 2}}
    };
    VkBufferCreateInfo vbInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    vbInfo.size = sizeof(verts);
    vbInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    vkCreateBuffer(device_, &vbInfo, nullptr, &vertexBuffer_);
    VkMemoryRequirements vbReq;
    vkGetBufferMemoryRequirements(device_, vertexBuffer_, &vbReq);
    VkMemoryAllocateInfo vbAlloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    vbAlloc.allocationSize = vbReq.size;
    vbAlloc.memoryTypeIndex = findMemoryType(vbReq.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device_, &vbAlloc, nullptr, &vertexMemory_);
    vkBindBufferMemory(device_, vertexBuffer_, vertexMemory_, 0);
    void *vbMapped;
    vkMapMemory(device_, vertexMemory_, 0, sizeof(verts), 0, &vbMapped);
    memcpy(vbMapped, verts, sizeof(verts));
    vkUnmapMemory(device_, vertexMemory_);

    // Push constant range for sizes + params
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pushRange.offset = 0;
    pushRange.size = 128; // enough for sizes + 9 params

    VkPipelineLayoutCreateInfo plInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &multiPassDescLayout_;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &pushRange;
    return vkCreatePipelineLayout(device_, &plInfo, nullptr, &multiPassPipelineLayout_) == VK_SUCCESS;
}

bool VulkanRenderer::createPassPipeline(VkPassResources &pass) {
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = pass.vertModule;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = pass.fragModule;
    stages[1].pName = "main";

    VkVertexInputBindingDescription vbBinding{0, 24, VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription vbAttribs[2] = {
        {0, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 0},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, 16}
    };
    VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &vbBinding;
    vertexInput.vertexAttributeDescriptionCount = 2;
    vertexInput.pVertexAttributeDescriptions = vbAttribs;
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewportState{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkDynamicState dynStates[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
    VkPipelineDynamicStateCreateInfo dynamicState{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynStates;

    VkPipelineRasterizationStateCreateInfo rasterizer{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;

    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAtt{};
    blendAtt.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo colorBlend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &blendAtt;

    VkGraphicsPipelineCreateInfo pInfo{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pInfo.stageCount = 2;
    pInfo.pStages = stages;
    pInfo.pVertexInputState = &vertexInput;
    pInfo.pInputAssemblyState = &inputAssembly;
    pInfo.pViewportState = &viewportState;
    pInfo.pRasterizationState = &rasterizer;
    pInfo.pMultisampleState = &ms;
    pInfo.pColorBlendState = &colorBlend;
    pInfo.pDynamicState = &dynamicState;
    pInfo.layout = multiPassPipelineLayout_;
    // Last pass renders to swapchain, others to intermediate
    pInfo.renderPass = (&pass == &passes_.back()) ? renderPass_ : intermediateRenderPass_;

    return vkCreateGraphicsPipelines(device_, pipelineCache_, 1, &pInfo, nullptr, &pass.pipeline) == VK_SUCCESS;
}

bool VulkanRenderer::createPassFbo(VkPassResources &pass, uint32_t w, uint32_t h) {
    pass.width = w;
    pass.height = h;

    VkImageCreateInfo imgInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {w, h, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    vkCreateImage(device_, &imgInfo, nullptr, &pass.image);

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, pass.image, &memReq);
    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(device_, &allocInfo, nullptr, &pass.memory);
    vkBindImageMemory(device_, pass.image, pass.memory, 0);

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = pass.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCreateImageView(device_, &viewInfo, nullptr, &pass.view);

    VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbInfo.renderPass = intermediateRenderPass_;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &pass.view;
    fbInfo.width = w;
    fbInfo.height = h;
    fbInfo.layers = 1;
    return vkCreateFramebuffer(device_, &fbInfo, nullptr, &pass.framebuffer) == VK_SUCCESS;
}

bool VulkanRenderer::loadPreset(const std::vector<VkPassConfig> &configs) {
    unloadPreset();
    if (configs.empty()) return false;

    if (intermediateRenderPass_ == VK_NULL_HANDLE && !createIntermediateRenderPass()) return false;
    if (multiPassDescLayout_ == VK_NULL_HANDLE && !createMultiPassLayouts()) return false;

    passes_.resize(configs.size());
    for (size_t i = 0; i < configs.size(); i++) {
        auto &pass = passes_[i];
        auto &cfg = configs[i];
        pass.filterLinear = cfg.filterLinear;
        pass.scaleType = cfg.scaleType;
        pass.scaleX = cfg.scaleX;
        pass.scaleY = cfg.scaleY;
        pass.needsOriginal = cfg.needsOriginal;

        pass.vertModule = createShaderModule(cfg.vertSpirv);
        pass.fragModule = createShaderModule(cfg.fragSpirv);
        if (pass.vertModule == VK_NULL_HANDLE || pass.fragModule == VK_NULL_HANDLE) {
            LOGE("Failed to create shader modules for pass %zu", i);
            unloadPreset();
            return false;
        }

        if (!createPassPipeline(pass)) {
            LOGE("Failed to create pipeline for pass %zu", i);
            unloadPreset();
            return false;
        }

        // Allocate descriptor set
        VkDescriptorSetAllocateInfo dsAlloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dsAlloc.descriptorPool = descriptorPool_;
        dsAlloc.descriptorSetCount = 1;
        dsAlloc.pSetLayouts = &multiPassDescLayout_;
        vkAllocateDescriptorSets(device_, &dsAlloc, &pass.descriptorSet);

        // FBOs created lazily when frame dimensions are known (except last pass)
    }

    presetLoaded_ = true;
    LOGI("Loaded %zu-pass preset", configs.size());
    return true;
}

void VulkanRenderer::unloadPreset() {
    if (!presetLoaded_) return;
    vkDeviceWaitIdle(device_);
    for (auto &pass : passes_) {
        if (pass.pipeline) vkDestroyPipeline(device_, pass.pipeline, nullptr);
        if (pass.vertModule) vkDestroyShaderModule(device_, pass.vertModule, nullptr);
        if (pass.fragModule) vkDestroyShaderModule(device_, pass.fragModule, nullptr);
        if (pass.framebuffer) vkDestroyFramebuffer(device_, pass.framebuffer, nullptr);
        if (pass.view) vkDestroyImageView(device_, pass.view, nullptr);
        if (pass.image) vkDestroyImage(device_, pass.image, nullptr);
        if (pass.memory) vkFreeMemory(device_, pass.memory, nullptr);
    }
    passes_.clear();
    presetLoaded_ = false;
}

void VulkanRenderer::renderMultiPass() {
    // Ensure intermediate FBOs exist
    for (size_t i = 0; i + 1 < passes_.size(); i++) {
        auto &pass = passes_[i];
        uint32_t w, h;
        if (pass.scaleType == 1) { // viewport
            w = (uint32_t)(vpW_ * pass.scaleX);
            h = (uint32_t)(vpH_ * pass.scaleY);
        } else { // source
            w = (uint32_t)(frameWidth_ * pass.scaleX);
            h = (uint32_t)(frameHeight_ * pass.scaleY);
        }
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        if (pass.width != w || pass.height != h) {
            if (pass.framebuffer) {
                vkDeviceWaitIdle(device_);
                vkDestroyFramebuffer(device_, pass.framebuffer, nullptr);
                vkDestroyImageView(device_, pass.view, nullptr);
                vkDestroyImage(device_, pass.image, nullptr);
                vkFreeMemory(device_, pass.memory, nullptr);
                pass.framebuffer = VK_NULL_HANDLE;
            }
            createPassFbo(pass, w, h);
        }
    }

    // Build push constant data
    struct PushData {
        float sourceSize[4];
        float originalSize[4];
        float outputSize[4];
        uint32_t frameCount;
        float params[9]; // curvature, scanline, mask_dark, vignette, glow_strength, sweep, sweep_bright, crt_brightness, noise_amount
    } push{};

    push.originalSize[0] = (float)frameWidth_;
    push.originalSize[1] = (float)frameHeight_;
    push.originalSize[2] = 1.0f / frameWidth_;
    push.originalSize[3] = 1.0f / frameHeight_;
    push.frameCount = totalFrames_;

    const char *paramNames[] = {"curvature", "scanline", "mask_dark", "vignette", "glow_strength", "sweep", "sweep_bright", "crt_brightness", "noise_amount"};
    float paramDefaults[] = {1.7f, 0.75f, 0.3f, 0.85f, 0.25f, 1.0f, 0.35f, 1.0f, 0.15f};
    for (int i = 0; i < 9; i++) {
        auto it = params_.find(paramNames[i]);
        push.params[i] = (it != params_.end()) ? it->second : paramDefaults[i];
    }

    VkCommandBuffer cmd = commandBuffers_[0];

    for (size_t i = 0; i < passes_.size(); i++) {
        auto &pass = passes_[i];
        bool isLast = (i + 1 == passes_.size());

        // Update source size for this pass
        if (i == 0) {
            push.sourceSize[0] = (float)frameWidth_;
            push.sourceSize[1] = (float)frameHeight_;
        } else {
            push.sourceSize[0] = (float)passes_[i-1].width;
            push.sourceSize[1] = (float)passes_[i-1].height;
        }
        push.sourceSize[2] = 1.0f / push.sourceSize[0];
        push.sourceSize[3] = 1.0f / push.sourceSize[1];

        if (isLast) {
            push.outputSize[0] = (float)vpW_;
            push.outputSize[1] = (float)vpH_;
        } else {
            push.outputSize[0] = (float)pass.width;
            push.outputSize[1] = (float)pass.height;
        }
        push.outputSize[2] = 1.0f / push.outputSize[0];
        push.outputSize[3] = 1.0f / push.outputSize[1];

        // Update descriptors: binding 0=UBO(MVP), 2=Source, 3=Original
        VkDescriptorBufferInfo uboInfo{mvpBuffer_, 0, 64};

        VkDescriptorImageInfo sourceInfo{};
        sourceInfo.sampler = frameSampler_;
        sourceInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        sourceInfo.imageView = (i == 0) ? frameView_ : passes_[i-1].view;

        VkDescriptorImageInfo origInfo{frameSampler_, frameView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};

        VkWriteDescriptorSet writes[3]{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = pass.descriptorSet;
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[0].pBufferInfo = &uboInfo;
        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = pass.descriptorSet;
        writes[1].dstBinding = 2;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[1].pImageInfo = &sourceInfo;
        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = pass.descriptorSet;
        writes[2].dstBinding = 3;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[2].pImageInfo = &origInfo;
        vkUpdateDescriptorSets(device_, 3, writes, 0, nullptr);

        // Begin render pass
        VkClearValue clear = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
        VkRenderPassBeginInfo rpBegin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rpBegin.clearValueCount = 1;
        rpBegin.pClearValues = &clear;

        if (isLast) {
            // Render to current swapchain image — handled by caller
            // We break here and let renderFrame do the last pass to the swapchain
            // Actually, we handle it inline
            rpBegin.renderPass = renderPass_;
            // We need the swapchain framebuffer — this is passed in from renderFrame
            // For now, store the last pass index and handle in renderFrame
            break; // handled in renderFrame
        } else {
            rpBegin.renderPass = intermediateRenderPass_;
            rpBegin.framebuffer = pass.framebuffer;
            rpBegin.renderArea.extent = {pass.width, pass.height};
        }

        vkCmdBeginRenderPass(cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, multiPassPipelineLayout_, 0, 1, &pass.descriptorSet, 0, nullptr);
        vkCmdPushConstants(cmd, multiPassPipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);

        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer_, &offset);
        VkViewport vp{0, 0, (float)pass.width, (float)pass.height, 0, 1};
        vkCmdSetViewport(cmd, 0, 1, &vp);
        VkRect2D sc{{0, 0}, {pass.width, pass.height}};
        vkCmdSetScissor(cmd, 0, 1, &sc);

        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
    }

    // Last pass renders to swapchain — done inline in renderFrame
}

void VulkanRenderer::destroy() {
    if (device_ == VK_NULL_HANDLE) return;
    vkDeviceWaitIdle(device_);

    // Save pipeline cache to disk
    if (pipelineCache_ && !cachePath_.empty()) {
        size_t sz = 0;
        vkGetPipelineCacheData(device_, pipelineCache_, &sz, nullptr);
        if (sz > 0) {
            std::vector<uint8_t> data(sz);
            vkGetPipelineCacheData(device_, pipelineCache_, &sz, data.data());
            FILE *f = fopen(cachePath_.c_str(), "wb");
            if (f) { fwrite(data.data(), 1, sz, f); fclose(f); }
        }
    }
    if (pipelineCache_) vkDestroyPipelineCache(device_, pipelineCache_, nullptr);

    unloadOverlay();
    unloadPreset();
    if (overlayPipeline_) vkDestroyPipeline(device_, overlayPipeline_, nullptr);
    if (overlayRenderPass_) vkDestroyRenderPass(device_, overlayRenderPass_, nullptr);
    if (intermediateRenderPass_) vkDestroyRenderPass(device_, intermediateRenderPass_, nullptr);
    if (multiPassDescLayout_) vkDestroyDescriptorSetLayout(device_, multiPassDescLayout_, nullptr);
    if (multiPassPipelineLayout_) vkDestroyPipelineLayout(device_, multiPassPipelineLayout_, nullptr);
    if (mvpBuffer_) { vkDestroyBuffer(device_, mvpBuffer_, nullptr); vkFreeMemory(device_, mvpMemory_, nullptr); }
    if (vertexBuffer_) { vkDestroyBuffer(device_, vertexBuffer_, nullptr); vkFreeMemory(device_, vertexMemory_, nullptr); }

    if (frameImage_) {
        vkDestroyImageView(device_, frameView_, nullptr);
        vkDestroyImage(device_, frameImage_, nullptr);
        vkFreeMemory(device_, frameMemory_, nullptr);
        vkDestroySampler(device_, frameSampler_, nullptr);
        vkDestroyBuffer(device_, stagingBuffer_, nullptr);
        vkFreeMemory(device_, stagingMemory_, nullptr);
    }

    vkDestroyPipeline(device_, passthroughPipeline_, nullptr);
    vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
    vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);

    for (auto fb : swapchainFramebuffers_) vkDestroyFramebuffer(device_, fb, nullptr);
    for (auto iv : swapchainViews_) vkDestroyImageView(device_, iv, nullptr);
    vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    vkDestroyRenderPass(device_, renderPass_, nullptr);

    vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr);
    vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr);
    vkDestroyFence(device_, inFlightFence_, nullptr);
    vkDestroyCommandPool(device_, commandPool_, nullptr);

    vkDestroyDevice(device_, nullptr);
    vkDestroySurfaceKHR(instance_, surface_, nullptr);
    vkDestroyInstance(instance_, nullptr);

    LOGI("Vulkan renderer destroyed");
}
