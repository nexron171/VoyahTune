#pragma once

#include <stdint.h>

#define VOYAHTUNE_BOOTSTRAP_SCHEMA 2u
#define VOYAHTUNE_CONTEXTLESS_TARGET 1u

typedef struct {
    uint32_t schema;
    uint32_t size;
    uint32_t flags;
    uint32_t startup_timeout_millis;
    uint64_t original_signal_mask;
    int32_t pid;
    int32_t tracee_tid;
    uint32_t uid;
    char boot_id[64];
    char start_ticks[32];
    char native_id[80];
    char runtime_id[80];
    char dex_sha256[65];
    char dex_path[4096];
} VoyahtuneBootstrapArguments;
