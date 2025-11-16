#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include "smoldtb/smoldtb.h"


#define DTB_MAX_SIZE 0x10000 // 64KB
static uint8_t s_dtb_tree[DTB_MAX_SIZE];
static size_t memory_offset = 0;

void* __dtb_malloc(size_t length) {
    if (memory_offset + length > DTB_MAX_SIZE) {
        return NULL;
    }
    void* ptr = &s_dtb_tree[memory_offset];
    memory_offset += length;
    return ptr;
}

void __dtb_free(void* ptr, size_t length) {
    (void)ptr;    // Suppress unused parameter warning
    (void)length; // Suppress unused parameter warning
    memset(ptr, 0, length); // Clear the memory
    memory_offset -= length; // This is a simple implementation, not a real free
}

void __dtb_on_error(const char* msg) {
    printf("[ERROR] %s\n", msg);
}

void __parse_dtb(uint32_t hart, void* dtb_blob) {
    if (dtb_blob == NULL) {
        return;
    }
    if (hart != 0) {
        return;
    }
    dtb_ops ops = {
        .malloc = __dtb_malloc,
        .free = __dtb_free,
        .on_error = __dtb_on_error
    };
    dtb_init((uintptr_t)dtb_blob, ops);
}