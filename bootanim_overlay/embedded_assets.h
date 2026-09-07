#pragma once

#include <cstddef>
#include <cstdint>

namespace bootanim_overlay {

struct EmbeddedPng {
    int group_id;
    int frame_index;
    const char* name;
    const unsigned char* data;
    size_t size;
};

extern const EmbeddedPng kEmbeddedPngs[];
extern const size_t kEmbeddedPngCount;

}  // namespace bootanim_overlay
