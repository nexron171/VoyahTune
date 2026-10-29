#pragma once

#include <asm/ptrace.h>
#include <elf.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__)
typedef struct user_pt_regs GeneralRegisters;
typedef struct user_fpsimd_state FloatingRegisters;
#define FLOATING_REGISTER_SET NT_FPREGSET
#define ARGUMENT_REGISTERS 8
#define STACK_ALIGNMENT 16

static uint64_t register_pc(const GeneralRegisters* state) {
    return state->pc;
}
static uint64_t register_sp(const GeneralRegisters* state) {
    return state->sp;
}
static uint64_t register_result(const GeneralRegisters* state) {
    return state->regs[0];
}
static uint64_t syscall_number(const GeneralRegisters* state) {
    return state->regs[8];
}
static uint64_t syscall_marker(const GeneralRegisters* state) {
    return state->regs[7];
}
static void copy_syscall_marker(GeneralRegisters* state, const GeneralRegisters* current) {
    state->regs[7] = current->regs[7];
}
static void set_argument(GeneralRegisters* state, size_t index, uint64_t value) {
    state->regs[index] = value;
}
static void set_stack(GeneralRegisters* state, uint64_t value) {
    state->sp = value;
}
static void set_entry(GeneralRegisters* state, uint64_t value) {
    state->pc = value;
}
static void set_return(GeneralRegisters* state, uint64_t value) {
    state->regs[30] = value;
}
static size_t syscall_instruction_size(const GeneralRegisters* state) {
    (void)state;
    return 4;
}
static bool syscall_instruction(uint32_t instruction, size_t size) {
    (void)size;
    return instruction == 0xd4000001;
}
static bool trap_instruction(uint32_t instruction, uint64_t address) {
    (void)address;
    return (instruction & 0xffe0001f) == 0xd4200000;
}
static uint64_t instruction_address(uint64_t address) {
    return address;
}
static void rewind_syscall(GeneralRegisters* state) {
    state->pc -= 4;
}
#elif defined(__arm__)
typedef struct pt_regs GeneralRegisters;
typedef struct {
    unsigned char bytes[260];
} FloatingRegisters;
#define FLOATING_REGISTER_SET NT_ARM_VFP
#define ARGUMENT_REGISTERS 4
#define STACK_ALIGNMENT 8

static uint64_t register_pc(const GeneralRegisters* state) {
    return (uint32_t)state->uregs[15];
}
static uint64_t register_sp(const GeneralRegisters* state) {
    return (uint32_t)state->uregs[13];
}
static uint64_t register_result(const GeneralRegisters* state) {
    return (uint32_t)state->uregs[0];
}
static uint64_t syscall_number(const GeneralRegisters* state) {
    return (uint32_t)state->uregs[7];
}
static uint64_t syscall_marker(const GeneralRegisters* state) {
    return (uint32_t)state->uregs[12];
}
static void copy_syscall_marker(GeneralRegisters* state, const GeneralRegisters* current) {
    state->uregs[12] = current->uregs[12];
}
static void set_argument(GeneralRegisters* state, size_t index, uint64_t value) {
    state->uregs[index] = (uint32_t)value;
}
static void set_stack(GeneralRegisters* state, uint64_t value) {
    state->uregs[13] = (uint32_t)value;
}
static void set_entry(GeneralRegisters* state, uint64_t value) {
    state->uregs[15] = (uint32_t)value & ~1U;
    // Clear stale Thumb IT state when entering a different function.
    state->uregs[16] = (state->uregs[16] & ~0x0600fc20UL) | ((value & 1) ? 0x20 : 0);
}
static void set_return(GeneralRegisters* state, uint64_t value) {
    state->uregs[14] = (uint32_t)value;
}
static size_t syscall_instruction_size(const GeneralRegisters* state) {
    return (state->uregs[16] & 0x20) ? 2 : 4;
}
static bool syscall_instruction(uint32_t instruction, size_t size) {
    return size == 2 ? instruction == 0xdf00 : instruction == 0xef000000;
}
static bool trap_instruction(uint32_t instruction, uint64_t address) {
    if (address & 1)
        return (instruction & 0xff00) == 0xbe00 || (instruction & 0xff00) == 0xde00;
    return (instruction & 0xfff000f0) == 0xe1200070 || (instruction & 0xfff000f0) == 0xe7f000f0;
}
static uint64_t instruction_address(uint64_t address) {
    return address & ~UINT64_C(1);
}
static void rewind_syscall(GeneralRegisters* state) {
    state->uregs[15] -= syscall_instruction_size(state);
}
#else
#error Android ARM or ARM64 target required
#endif

static bool at_trap(const GeneralRegisters* state, uint64_t address, int signal) {
    return (signal == SIGTRAP || signal == SIGILL) && register_pc(state) == instruction_address(address);
}
