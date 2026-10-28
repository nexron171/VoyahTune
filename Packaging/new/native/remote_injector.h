#pragma once

#include <stddef.h>
#include <stdint.h>
#include "bootstrap_record.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    VOYAHTUNE_ATTACH_WAITING = 1,
    VOYAHTUNE_ATTACH_ATTACHED = 2,
    VOYAHTUNE_ATTACH_SAVED = 3,
    VOYAHTUNE_ATTACH_LOADING = 4,
    VOYAHTUNE_ATTACH_WORKER_START = 5,
    VOYAHTUNE_ATTACH_RESTORED = 6,
    VOYAHTUNE_ATTACH_DETACHED = 7,
    VOYAHTUNE_ATTACH_QUARANTINED = 8
} VoyahtuneAttachStage;

typedef struct {
    uint64_t dlopen;
    uint64_t dlsym;
    uint64_t dlclose;
    uint64_t dlerror;
    uint64_t mmap;
    uint64_t munmap;
    uint64_t breakpoint;
    uint64_t caller;
} VoyahtuneRemoteFunctions;

typedef struct {
    VoyahtuneBootstrapArguments bootstrap;
    VoyahtuneRemoteFunctions functions;
    const char* library_path;
    uint32_t acquire_timeout_millis;
    int progress_descriptor;
} VoyahtuneRemoteRequest;

typedef struct {
    uint32_t attached;
    uint32_t registers_saved;
    uint32_t restored;
    uint32_t detached;
    uint32_t library_loaded;
    uint32_t worker_started;
    uint32_t uncertain;
    uint32_t target_exited;
    uint64_t safe_pc;
    int32_t bootstrap_result;
} VoyahtuneRemoteResult;

void VoyahtuneResetInjectionCancellation(void);
void VoyahtuneCancelInjection(void);
int VoyahtuneRemoteInject(const VoyahtuneRemoteRequest* request, VoyahtuneRemoteResult* result,
                         char* error, size_t error_capacity);

#ifdef __cplusplus
}
#endif
