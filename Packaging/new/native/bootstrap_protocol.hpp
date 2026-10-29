#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include "bootstrap_record.h"

namespace voyahtune {

constexpr std::uint32_t kBootstrapSchema = VOYAHTUNE_BOOTSTRAP_SCHEMA;
constexpr std::uint32_t kAllowContextlessTarget = VOYAHTUNE_CONTEXTLESS_TARGET;
constexpr std::size_t kMaximumAgentFrame = 1024 * 1024;
constexpr std::size_t kRequestTokenSize = 16;

enum class AgentCommand : std::uint8_t {
    Status = 1,
    Apply = 2,
    Quiesce = 3,
};

using BootstrapArguments = VoyahtuneBootstrapArguments;

struct TargetIdentity {
    std::string boot_id;
    std::int32_t pid;
    std::uint32_t uid;
    std::string start_ticks;
};

TargetIdentity ReadCurrentIdentity();
std::string AgentSocketName(const TargetIdentity& identity);
std::string BootstrapMetadata(const BootstrapArguments& arguments);
const char* NativeRuntimeId();
bool TraceeDetached(const BootstrapArguments& arguments);

}  // namespace voyahtune

extern "C" __attribute__((visibility("default")))
int VoyahtuneAgentStart(const voyahtune::BootstrapArguments* arguments);
