// Freestanding WebAssembly hot-path helpers for the browser client.
//
// The Java/TeaVM client remains the source of truth.  This module only handles
// bulk array transforms where crossing the JS <-> Wasm boundary once per batch
// is cheaper than running per-element work in JavaScript.

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef int i32;
typedef long long i64;
typedef unsigned long long u64;

enum {
    GL_UNSIGNED_BYTE = 0x1401,
    GL_UNSIGNED_SHORT = 0x1403,
    GL_UNSIGNED_INT = 0x1405,
    MAX_INDICES = 1024 * 1024
};

__attribute__((aligned(16))) static u8 index_input[MAX_INDICES * 4];
__attribute__((aligned(16))) static u8 index_output[MAX_INDICES * 4];

static u32 last_output_type;
static u32 last_output_bytes;
static u32 last_min_index;
static u32 last_max_index;

__attribute__((used, visibility("default")))
u32 gaius_hotpath_version(void) {
    return 1;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_capacity(void) {
    return MAX_INDICES;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_input_ptr(void) {
    return (u32)(unsigned long)index_input;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_output_ptr(void) {
    return (u32)(unsigned long)index_output;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_type(void) {
    return last_output_type;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_bytes(void) {
    return last_output_bytes;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_min(void) {
    return last_min_index;
}

__attribute__((used, visibility("default")))
u32 gaius_shift_indices_last_max(void) {
    return last_max_index;
}

static u32 read_index(u32 type, u32 index) {
    if (type == GL_UNSIGNED_BYTE) {
        return ((u8 *)index_input)[index];
    }
    if (type == GL_UNSIGNED_SHORT) {
        return ((u16 *)index_input)[index];
    }
    return ((u32 *)index_input)[index];
}

__attribute__((used, visibility("default")))
i32 gaius_shift_indices(u32 type, u32 count, i32 base_vertex) {
    if (count == 0 || count > MAX_INDICES) {
        return 0;
    }
    if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT) {
        return 0;
    }

    u32 min_index = 0xffffffffu;
    u32 max_index = 0;
    for (u32 i = 0; i < count; i++) {
        i64 shifted = (i64)(u64)read_index(type, i) + (i64)base_vertex;
        if (shifted < 0 || shifted > 0xffffffffll) {
            return 0;
        }
        u32 value = (u32)shifted;
        if (value < min_index) {
            min_index = value;
        }
        if (value > max_index) {
            max_index = value;
        }
    }

    u32 output_type = GL_UNSIGNED_INT;
    if (max_index <= 255u && type == GL_UNSIGNED_BYTE) {
        output_type = GL_UNSIGNED_BYTE;
    } else if (max_index <= 65535u && type != GL_UNSIGNED_INT) {
        output_type = GL_UNSIGNED_SHORT;
    }

    if (output_type == GL_UNSIGNED_BYTE) {
        u8 *out = (u8 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = (u8)(read_index(type, i) + (u32)base_vertex);
        }
        last_output_bytes = count;
    } else if (output_type == GL_UNSIGNED_SHORT) {
        u16 *out = (u16 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = (u16)(read_index(type, i) + (u32)base_vertex);
        }
        last_output_bytes = count * 2u;
    } else {
        u32 *out = (u32 *)index_output;
        for (u32 i = 0; i < count; i++) {
            out[i] = read_index(type, i) + (u32)base_vertex;
        }
        last_output_bytes = count * 4u;
    }

    last_output_type = output_type;
    last_min_index = min_index;
    last_max_index = max_index;
    return 1;
}
