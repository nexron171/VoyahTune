#define _GNU_SOURCE
#include "remote_injector.h"

#include <asm/unistd.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "remote_registers.h"

#ifndef NT_ARM_SYSTEM_CALL
#define NT_ARM_SYSTEM_CALL 0x404
#endif
#ifndef NT_ARM_SVE
#define NT_ARM_SVE 0x405
#endif

static atomic_bool injection_cancelled;

void VoyahtuneResetInjectionCancellation(void) {
    atomic_store(&injection_cancelled, false);
}

void VoyahtuneCancelInjection(void) {
    atomic_store(&injection_cancelled, true);
}

typedef struct {
    const VoyahtuneRemoteRequest* request;
    VoyahtuneRemoteResult* result;
    char* error;
    size_t error_capacity;
    bool attached;
    bool stopped;
    bool changed;
    bool have_mask;
    bool have_registers;
    uint64_t signal_mask;
    GeneralRegisters original;
    FloatingRegisters floating;
    unsigned char sve[65536];
    size_t sve_size;
    siginfo_t deferred_signal;
    bool have_deferred_signal;
} TraceContext;

static int fail(TraceContext* context, int code, const char* operation) {
    if (context->error_capacity > 0 && context->error[0] == '\0') {
        snprintf(context->error, context->error_capacity, "%s: %s", operation, strerror(code));
    }
    return code;
}

static void stage(TraceContext* context, VoyahtuneAttachStage value) {
    uint32_t record = (uint32_t)value;
    if (context->request->progress_descriptor >= 0) {
        ssize_t written;
        do {
            written = write(context->request->progress_descriptor, &record, sizeof(record));
        } while (written < 0 && errno == EINTR);
    }
}

static uint64_t milliseconds(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
}

static int registers(TraceContext* context, bool write, unsigned int kind, void* buffer, size_t size) {
    struct iovec vector = {buffer, size};
    int request = write ? PTRACE_SETREGSET : PTRACE_GETREGSET;
    if (ptrace(request, context->request->bootstrap.pid, (void*)(uintptr_t)kind, &vector) != 0) {
        return fail(context, errno, write ? "restore-register-set" : "read-register-set");
    }
    if (vector.iov_len != size) {
        return fail(context, ENOEXEC, "unexpected-register-set-size");
    }
    return 0;
}

static int memory(TraceContext* context, bool write, uint64_t address, void* bytes, size_t size) {
    unsigned char* buffer = bytes;
    for (size_t offset = 0; offset < size;) {
        uint64_t aligned = (address + offset) & ~(uint64_t)(sizeof(long) - 1);
        size_t within = (size_t)(address + offset - aligned);
        size_t count = sizeof(long) - within;
        if (count > size - offset)
            count = size - offset;
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, context->request->bootstrap.pid, (void*)(uintptr_t)aligned, NULL);
        if (errno != 0)
            return fail(context, errno, "read-target-memory");
        if (write) {
            memcpy((unsigned char*)&word + within, buffer + offset, count);
            if (ptrace(PTRACE_POKEDATA, context->request->bootstrap.pid, (void*)(uintptr_t)aligned,
                       (void*)(uintptr_t)(unsigned long)word) != 0) {
                return fail(context, errno, "write-target-memory");
            }
        } else {
            memcpy(buffer + offset, (unsigned char*)&word + within, count);
        }
        offset += count;
    }
    return 0;
}

static int resume(TraceContext* context, int command, int signal) {
    if (ptrace(command, context->request->bootstrap.pid, NULL, (void*)(uintptr_t)signal) != 0) {
        return fail(context, errno, "resume-target");
    }
    context->stopped = false;
    return 0;
}

static int wait_stop(TraceContext* context, uint64_t deadline, int* status) {
    for (;;) {
        pid_t result = waitpid(context->request->bootstrap.pid, status, __WALL | WNOHANG);
        if (result == context->request->bootstrap.pid) {
            if (WIFEXITED(*status) || WIFSIGNALED(*status)) {
                context->result->target_exited = 1;
                context->result->detached = 1;
                context->attached = false;
                context->stopped = false;
                return fail(context, ESRCH, "target-exited");
            }
            if (WIFSTOPPED(*status)) {
                context->stopped = true;
                return 0;
            }
        } else if (result < 0 && errno != EINTR) {
            return fail(context, errno, "wait-target");
        }
        if (deadline != 0 && milliseconds() >= deadline)
            return ETIMEDOUT;
        struct timespec pause = {0, 5000000};
        nanosleep(&pause, NULL);
    }
}

static void quarantine(TraceContext* context) {
    context->result->uncertain = 1;
    stage(context, VOYAHTUNE_ATTACH_QUARANTINED);
    // Never overwrite an unfinished call frame or release its file-replacement lock.
    while (context->attached) {
        int status;
        int result = wait_stop(context, 0, &status);
        if (result != 0 && !context->result->target_exited) {
            struct timespec pause = {0, 100000000};
            nanosleep(&pause, NULL);
        }
    }
}

static int read_text(const char* path, char* buffer, size_t capacity) {
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0)
        return errno;
    ssize_t size;
    do {
        size = read(descriptor, buffer, capacity - 1);
    } while (size < 0 && errno == EINTR);
    int saved = errno;
    close(descriptor);
    if (size < 0)
        return saved;
    if ((size_t)size == capacity - 1)
        return EOVERFLOW;
    buffer[size] = '\0';
    return 0;
}

static int verify_identity(TraceContext* context) {
    char data[8192];
    char path[128];
    int code = read_text("/proc/sys/kernel/random/boot_id", data, sizeof(data));
    if (code != 0)
        return fail(context, code, "read-target-boot");
    data[strcspn(data, "\r\n")] = '\0';
    if (strcmp(data, context->request->bootstrap.boot_id) != 0)
        return fail(context, ESTALE, "target-boot-changed");
    snprintf(path, sizeof(path), "/proc/%d/stat", context->request->bootstrap.pid);
    code = read_text(path, data, sizeof(data));
    if (code != 0)
        return fail(context, code, "read-target-stat");
    char* end = strrchr(data, ')');
    if (end == NULL)
        return fail(context, EINVAL, "invalid-target-stat");
    char* state = NULL;
    char* field = strtok_r(end + 1, " \t\r\n", &state);
    for (int index = 0; index < 19 && field != NULL; index++) field = strtok_r(NULL, " \t\r\n", &state);
    if (field == NULL || strcmp(field, context->request->bootstrap.start_ticks) != 0) {
        return fail(context, ESTALE, "target-start-ticks-changed");
    }
    snprintf(path, sizeof(path), "/proc/%d/status", context->request->bootstrap.pid);
    code = read_text(path, data, sizeof(data));
    if (code != 0)
        return fail(context, code, "read-target-status");
    char* line = strstr(data, "\nUid:");
    unsigned int real_uid, effective_uid;
    if (line == NULL || sscanf(line + 5, "%u %u", &real_uid, &effective_uid) != 2 ||
        effective_uid != context->request->bootstrap.uid) {
        return fail(context, ESTALE, "target-uid-changed");
    }
    return 0;
}

static bool safe_syscall(uint64_t number) {
    return number == __NR_epoll_pwait || number == __NR_ppoll || number == __NR_nanosleep ||
           number == __NR_clock_nanosleep;
}

static int syscall_direction(TraceContext* context, const GeneralRegisters* current) {
    struct ptrace_syscall_info information;
    memset(&information, 0, sizeof(information));
    long size =
        ptrace(PTRACE_GET_SYSCALL_INFO, context->request->bootstrap.pid, (void*)sizeof(information), &information);
    if (size >= 1) {
        if (information.op == PTRACE_SYSCALL_INFO_ENTRY)
            return 0;
        if (information.op == PTRACE_SYSCALL_INFO_EXIT)
            return 1;
        return -1;
    }
    if ((errno == EINVAL || errno == EIO) && syscall_marker(current) <= 1)
        return (int)syscall_marker(current);
    return -1;
}

static int acquire_safe_stop(TraceContext* context) {
    uint64_t deadline = milliseconds() + context->request->acquire_timeout_millis;
    int signal = 0;
    while (!atomic_load(&injection_cancelled)) {
        int code = resume(context, PTRACE_SYSCALL, signal);
        if (code != 0)
            return code;
        signal = 0;
        int status;
        code = wait_stop(context, deadline, &status);
        if (code != 0)
            return fail(context, code, "wait-safe-syscall");
        if (WSTOPSIG(status) == (SIGTRAP | 0x80)) {
            GeneralRegisters current;
            code = registers(context, false, NT_PRSTATUS, &current, sizeof(current));
            if (code != 0)
                return code;
            if (syscall_direction(context, &current) == 0 && safe_syscall(syscall_number(&current))) {
                context->original = current;
                return 0;
            }
        } else if (((unsigned int)status >> 16) == PTRACE_EVENT_STOP) {
            if (WSTOPSIG(status) != SIGTRAP) {
                context->deferred_signal.si_signo = WSTOPSIG(status);
                return fail(context, EBUSY, "target-group-stopped");
            }
        } else {
            signal = WSTOPSIG(status);
        }
    }
    return fail(context, ECANCELED, "attach-cancelled");
}

static int park_at_breakpoint(TraceContext* context, bool exiting_syscall) {
    GeneralRegisters temporary = context->original;
    set_entry(&temporary, context->request->functions.breakpoint);
    int code = registers(context, true, NT_PRSTATUS, &temporary, sizeof(temporary));
    if (code != 0)
        return code;
    code = resume(context, PTRACE_CONT, 0);
    if (code != 0)
        return code;
    int status;
    code = wait_stop(context, 0, &status);
    if (code != 0)
        return code;
    GeneralRegisters current;
    code = registers(context, false, NT_PRSTATUS, &current, sizeof(current));
    if (code != 0)
        return code;
    if (!at_trap(&current, context->request->functions.breakpoint, WSTOPSIG(status)) ||
        ((unsigned int)status >> 16) != 0) {
        fail(context, EPROTO, "unexpected-target-park-stop");
        quarantine(context);
        return EPROTO;
    }
    if (exiting_syscall)
        copy_syscall_marker(&context->original, &current);
    return 0;
}

static int cancel_syscall(TraceContext* context) {
#if defined(__arm__)
    // The compat ARM tracer exposes PTRACE_SET_SYSCALL, not NT_ARM_SYSTEM_CALL.
    if (ptrace(23, context->request->bootstrap.pid, NULL, (void*)(intptr_t)-1) != 0)
        return fail(context, errno, "cancel-target-syscall");
    return 0;
#else
    int number = -1;
    return registers(context, true, NT_ARM_SYSTEM_CALL, &number, sizeof(number));
#endif
}

static int save_context(TraceContext* context) {
    int code = registers(context, false, FLOATING_REGISTER_SET, &context->floating, sizeof(context->floating));
    if (code != 0)
        return code;
#if defined(__aarch64__)
    struct iovec sve = {context->sve, sizeof(context->sve)};
    if (ptrace(PTRACE_GETREGSET, context->request->bootstrap.pid, (void*)NT_ARM_SVE, &sve) == 0) {
        if (sve.iov_len < sizeof(struct user_sve_header) || sve.iov_len > sizeof(context->sve) ||
            ((struct user_sve_header*)context->sve)->size > sve.iov_len) {
            return fail(context, EOVERFLOW, "unsupported-sve-register-size");
        }
        context->sve_size = sve.iov_len;
    } else if (errno != EINVAL && errno != EIO && errno != ENODEV) {
        return fail(context, errno, "read-sve-registers");
    }
#endif
    if (ptrace(PTRACE_GETSIGMASK, context->request->bootstrap.pid, (void*)sizeof(context->signal_mask),
               &context->signal_mask) != 0) {
        return fail(context, errno, "read-target-signal-mask");
    }
    context->have_mask = true;
    uint32_t instruction = 0;
    size_t instruction_size = syscall_instruction_size(&context->original);
    code = memory(context, false, register_pc(&context->original) - instruction_size, &instruction, instruction_size);
    if (code != 0)
        return code;
    if (!syscall_instruction(instruction, instruction_size))
        return fail(context, ENOEXEC, "safe-syscall-has-no-svc");
    code = memory(context, false, instruction_address(context->request->functions.breakpoint), &instruction,
                  (context->request->functions.breakpoint & 1) ? 2 : 4);
    if (code != 0)
        return code;
    if (!trap_instruction(instruction, context->request->functions.breakpoint))
        return fail(context, ENOEXEC, "target-breakpoint-changed");
    context->have_registers = true;
    uint64_t blocked = UINT64_MAX & ~(UINT64_C(1) << (SIGKILL - 1)) & ~(UINT64_C(1) << (SIGSTOP - 1));
    if (ptrace(PTRACE_SETSIGMASK, context->request->bootstrap.pid, (void*)sizeof(blocked), &blocked) != 0) {
        return fail(context, errno, "block-target-signals");
    }
    context->changed = true;
    code = cancel_syscall(context);
    if (code != 0)
        return code;
    code = resume(context, PTRACE_SYSCALL, 0);
    if (code != 0)
        return code;
    int status;
    code = wait_stop(context, 0, &status);
    if (code != 0)
        return code;
    if (WSTOPSIG(status) != (SIGTRAP | 0x80)) {
        fail(context, EPROTO, "unexpected-cancelled-syscall-stop");
        quarantine(context);
        return EPROTO;
    }
    code = park_at_breakpoint(context, true);
    if (code != 0)
        return code;
    rewind_syscall(&context->original);
    context->result->registers_saved = 1;
    context->result->safe_pc = register_pc(&context->original);
    stage(context, VOYAHTUNE_ATTACH_SAVED);
    return 0;
}

static int remote_call(TraceContext* context, uint64_t function, const uint64_t* arguments, size_t count,
                       uint64_t* returned) {
    GeneralRegisters call = context->original;
    if (count > 8)
        return fail(context, EINVAL, "remote-call-argument-limit");
    for (size_t index = 0; index < count && index < ARGUMENT_REGISTERS; index++)
        set_argument(&call, index, arguments[index]);
    uint64_t stack = (register_sp(&call) & ~(uint64_t)(STACK_ALIGNMENT - 1)) - 256;
    set_stack(&call, stack);
    for (size_t index = ARGUMENT_REGISTERS; index < count; index++) {
        uintptr_t value = (uintptr_t)arguments[index];
        int code = memory(context, true, stack + (index - ARGUMENT_REGISTERS) * sizeof(value), &value, sizeof(value));
        if (code != 0)
            return code;
    }
    set_entry(&call, function);
    set_return(&call, context->request->functions.breakpoint);
    int code = registers(context, true, NT_PRSTATUS, &call, sizeof(call));
    if (code != 0)
        return code;
    code = resume(context, PTRACE_CONT, 0);
    if (code != 0)
        return code;
    for (;;) {
        int status;
        code = wait_stop(context, 0, &status);
        if (code != 0)
            return code;
        code = registers(context, false, NT_PRSTATUS, &call, sizeof(call));
        if (code != 0)
            return code;
        if (at_trap(&call, context->request->functions.breakpoint, WSTOPSIG(status)) &&
            ((unsigned int)status >> 16) == 0) {
            *returned = register_result(&call);
            return 0;
        }
        siginfo_t signal;
        memset(&signal, 0, sizeof(signal));
        if (ptrace(PTRACE_GETSIGINFO, context->request->bootstrap.pid, NULL, &signal) == 0 && signal.si_code <= 0 &&
            context->deferred_signal.si_signo == 0) {
            context->deferred_signal = signal;
            context->have_deferred_signal = true;
            code = resume(context, PTRACE_CONT, 0);
            if (code != 0)
                return code;
            continue;
        }
        fail(context, EPROTO, "unexpected-remote-call-stop");
        quarantine(context);
        return EPROTO;
    }
}

static int restore_context(TraceContext* context) {
    if (!context->attached)
        return context->result->target_exited ? ESRCH : 0;
    if (!context->stopped) {
        if (ptrace(PTRACE_INTERRUPT, context->request->bootstrap.pid, NULL, NULL) != 0) {
            return fail(context, errno, "interrupt-for-cleanup");
        }
        int status;
        int code = wait_stop(context, 0, &status);
        if (code != 0)
            return code;
    }
    if (context->changed) {
        if (!context->result->registers_saved) {
            fail(context, EOWNERDEAD, "context-save-incomplete");
            quarantine(context);
            return EOWNERDEAD;
        }
        int code = context->sve_size != 0
                       ? registers(context, true, NT_ARM_SVE, context->sve, context->sve_size)
                       : registers(context, true, FLOATING_REGISTER_SET, &context->floating, sizeof(context->floating));
        if (code != 0)
            return code;
        code = cancel_syscall(context);
        if (code != 0)
            return code;
        code = registers(context, true, NT_PRSTATUS, &context->original, sizeof(context->original));
        if (code != 0)
            return code;
        GeneralRegisters verified;
        code = registers(context, false, NT_PRSTATUS, &verified, sizeof(verified));
        if (code != 0)
            return code;
        if (memcmp(&verified, &context->original, sizeof(verified)) != 0)
            return fail(context, EIO, "target-register-verification-failed");
    }
    if (context->have_mask && ptrace(PTRACE_SETSIGMASK, context->request->bootstrap.pid,
                                     (void*)sizeof(context->signal_mask), &context->signal_mask) != 0) {
        return fail(context, errno, "restore-target-signal-mask");
    }
    context->result->restored = 1;
    stage(context, VOYAHTUNE_ATTACH_RESTORED);
    int signal = context->deferred_signal.si_signo;
    if (signal != 0 && context->have_deferred_signal &&
        ptrace(PTRACE_SETSIGINFO, context->request->bootstrap.pid, NULL, &context->deferred_signal) != 0) {
        return fail(context, errno, "restore-deferred-signal");
    }
    if (ptrace(PTRACE_DETACH, context->request->bootstrap.pid, NULL, (void*)(uintptr_t)signal) != 0) {
        return fail(context, errno, "detach-target");
    }
    context->attached = false;
    context->stopped = false;
    context->result->detached = 1;
    stage(context, VOYAHTUNE_ATTACH_DETACHED);
    return 0;
}

static int loader_failure(TraceContext* context) {
    uint64_t address = 0;
    int code = remote_call(context, context->request->functions.dlerror, NULL, 0, &address);
    if (code != 0)
        return code;
    char message[512];
    memset(message, 0, sizeof(message));
    for (size_t index = 0; address != 0 && index + 1 < sizeof(message); index++) {
        code = memory(context, false, address + index, &message[index], 1);
        if (code != 0 || message[index] == '\0')
            break;
    }
    if (context->error_capacity > 0 && context->error[0] == '\0') {
        snprintf(context->error, context->error_capacity, "remote-dlopen: %s",
                 address == 0 ? "no loader diagnostic" : message);
    }
    return ELIBACC;
}

static int load_worker(TraceContext* context) {
    const VoyahtuneRemoteRequest* request = context->request;
    uint64_t arguments[6] = {0, 16384, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, UINT64_MAX, 0};
    uint64_t scratch = 0;
    uint64_t handle = 0;
    uint64_t returned = 0;
    int code = remote_call(context, request->functions.mmap, arguments, 6, &scratch);
    if (code != 0)
        return code;
    if (scratch == 0 || scratch >= UINTPTR_MAX - 4095)
        return fail(context, ENOMEM, "remote-mmap");
    uint64_t library_path = scratch + 8192;
    uint64_t entry_name = scratch + 12288;
    VoyahtuneBootstrapArguments bootstrap = request->bootstrap;
    bootstrap.original_signal_mask = context->signal_mask;
    code = memory(context, true, scratch, &bootstrap, sizeof(bootstrap));
    if (code == 0)
        code = memory(context, true, library_path, (void*)request->library_path, strlen(request->library_path) + 1);
    char symbol[] = "VoyahtuneAgentStart";
    if (code == 0)
        code = memory(context, true, entry_name, symbol, sizeof(symbol));
    if (code == 0 && atomic_load(&injection_cancelled))
        code = fail(context, ECANCELED, "attach-cancelled");
    if (code == 0) {
        stage(context, VOYAHTUNE_ATTACH_LOADING);
        uint64_t open_arguments[] = {library_path, 2, request->functions.caller};
        code = remote_call(context, request->functions.dlopen, open_arguments, 3, &handle);
        if (code == 0 && handle == 0)
            code = loader_failure(context);
        if (code == 0)
            context->result->library_loaded = 1;
    }
    if (code == 0 && atomic_load(&injection_cancelled))
        code = fail(context, ECANCELED, "attach-cancelled-after-load");
    if (code == 0) {
        uint64_t symbol_arguments[] = {handle, entry_name, request->functions.caller};
        uint64_t entry = 0;
        code = remote_call(context, request->functions.dlsym, symbol_arguments, 3, &entry);
        if (code == 0 && entry == 0)
            code = fail(context, ENOENT, "remote-bootstrap-symbol");
        if (code == 0) {
            stage(context, VOYAHTUNE_ATTACH_WORKER_START);
            uint64_t start_arguments[] = {scratch};
            code = remote_call(context, entry, start_arguments, 1, &returned);
            if (code == 0) {
                context->result->bootstrap_result = (int32_t)returned;
                if ((int32_t)returned == 0)
                    context->result->worker_started = 1;
                else
                    code = fail(context, EPROTO, "remote-bootstrap-rejected");
            }
        }
    }
    if (context->attached && handle != 0 && !context->result->worker_started) {
        uint64_t close_arguments[] = {handle};
        int cleanup = remote_call(context, request->functions.dlclose, close_arguments, 1, &returned);
        if (cleanup == 0 && (int32_t)returned != 0)
            cleanup = fail(context, EIO, "remote-dlclose-rejected");
        if (cleanup != 0)
            code = cleanup;
    }
    if (context->attached) {
        uint64_t unmap_arguments[] = {scratch, 16384};
        int cleanup = remote_call(context, request->functions.munmap, unmap_arguments, 2, &returned);
        if (cleanup == 0 && (int32_t)returned != 0)
            cleanup = fail(context, EIO, "remote-munmap-rejected");
        if (cleanup != 0)
            code = cleanup;
    }
    if (code == 0 && atomic_load(&injection_cancelled))
        code = fail(context, ECANCELED, "attach-cancelled-after-start");
    return code;
}

static bool bounded_string(const char* value, size_t capacity) {
    return value[0] != '\0' && memchr(value, '\0', capacity) != NULL;
}

int VoyahtuneRemoteInject(const VoyahtuneRemoteRequest* request, VoyahtuneRemoteResult* result, char* error,
                          size_t error_capacity) {
    if (request == NULL || result == NULL || error == NULL || error_capacity == 0)
        return EINVAL;
    memset(result, 0, sizeof(*result));
    error[0] = '\0';
    TraceContext context;
    memset(&context, 0, sizeof(context));
    context.request = request;
    context.result = result;
    context.error = error;
    context.error_capacity = error_capacity;
    const VoyahtuneBootstrapArguments* bootstrap = &request->bootstrap;
    if (bootstrap->schema != VOYAHTUNE_BOOTSTRAP_SCHEMA || bootstrap->size != sizeof(*bootstrap) ||
        bootstrap->flags != 0 || bootstrap->pid <= 1 || bootstrap->tracee_tid != bootstrap->pid ||
        bootstrap->startup_timeout_millis < 1 || bootstrap->startup_timeout_millis > 60000 ||
        request->acquire_timeout_millis < 1 || request->acquire_timeout_millis > 60000 ||
        request->functions.dlopen == 0 || request->functions.dlsym == 0 || request->functions.dlclose == 0 ||
        request->functions.dlerror == 0 || request->functions.mmap == 0 || request->functions.munmap == 0 ||
        request->functions.breakpoint == 0 || request->functions.caller == 0 || request->library_path == NULL ||
        request->library_path[0] != '/' || strnlen(request->library_path, 4096) >= 4096 ||
        !bounded_string(bootstrap->boot_id, sizeof(bootstrap->boot_id)) ||
        !bounded_string(bootstrap->start_ticks, sizeof(bootstrap->start_ticks)) ||
        !bounded_string(bootstrap->native_id, sizeof(bootstrap->native_id)) ||
        !bounded_string(bootstrap->runtime_id, sizeof(bootstrap->runtime_id)) ||
        !bounded_string(bootstrap->dex_sha256, sizeof(bootstrap->dex_sha256)) ||
        !bounded_string(bootstrap->dex_path, sizeof(bootstrap->dex_path))) {
        return fail(&context, EINVAL, "invalid-bootstrap-request");
    }
    int code = verify_identity(&context);
    if (code != 0)
        return code;
    if (atomic_load(&injection_cancelled)) {
        result->restored = result->detached = 1;
        return fail(&context, ECANCELED, "attach-cancelled-before-seize");
    }
    stage(&context, VOYAHTUNE_ATTACH_WAITING);
    if (ptrace(PTRACE_SEIZE, bootstrap->pid, NULL, (void*)PTRACE_O_TRACESYSGOOD) != 0) {
        result->restored = result->detached = 1;
        return fail(&context, errno, "seize-target");
    }
    context.attached = true;
    result->attached = 1;
    if (ptrace(PTRACE_INTERRUPT, bootstrap->pid, NULL, NULL) != 0) {
        code = fail(&context, errno, "interrupt-target");
    } else {
        int status;
        code = wait_stop(&context, 0, &status);
        if (code == 0 && (((unsigned int)status >> 16) != PTRACE_EVENT_STOP || WSTOPSIG(status) != SIGTRAP)) {
            context.deferred_signal.si_signo = WSTOPSIG(status);
            code = fail(&context, EBUSY, "target-was-not-runnable");
        }
    }
    if (code == 0) {
        stage(&context, VOYAHTUNE_ATTACH_ATTACHED);
        code = verify_identity(&context);
    }
    if (code == 0)
        code = acquire_safe_stop(&context);
    if (code == 0)
        code = save_context(&context);
    if (code == 0 && atomic_load(&injection_cancelled))
        code = fail(&context, ECANCELED, "attach-cancelled-after-save");
    if (code == 0)
        code = load_worker(&context);
    int cleanup = restore_context(&context);
    if (cleanup != 0 && context.attached) {
        quarantine(&context);
        code = cleanup;
    }
    return code != 0 ? code : cleanup;
}
