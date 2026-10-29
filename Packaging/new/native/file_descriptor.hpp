#pragma once

#include <unistd.h>
#include <utility>

namespace voyahtune {

class FileDescriptor {
public:
    explicit FileDescriptor(int descriptor = -1) : descriptor_(descriptor) {}
    ~FileDescriptor() { if (descriptor_ >= 0) ::close(descriptor_); }
    FileDescriptor(const FileDescriptor&) = delete;
    FileDescriptor& operator=(const FileDescriptor&) = delete;
    FileDescriptor(FileDescriptor&& other) noexcept : descriptor_(other.release()) {}

    int get() const { return descriptor_; }
    int release() { return std::exchange(descriptor_, -1); }

private:
    int descriptor_;
};

}  // namespace voyahtune
