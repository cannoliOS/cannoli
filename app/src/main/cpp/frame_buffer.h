#pragma once
#include <cstdint>
#include <cstddef>

struct FrameBuffer {
    uint8_t *data;
    unsigned width;
    unsigned height;
    size_t pitch;
    unsigned pixel_format;
    bool ready;
};

#ifdef __cplusplus
extern "C" {
#endif

FrameBuffer *getFrameBuffer();
void markFrameConsumed();

#ifdef __cplusplus
}
#endif
