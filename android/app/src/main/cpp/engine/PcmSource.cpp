#include "PcmSource.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>

namespace phase {

bool PcmSource::open(const std::string& path, std::string* err) {
    close();
    fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd_ < 0) {
        if (err) *err = "open " + path + ": " + std::strerror(errno);
        return false;
    }
    struct stat st {};
    if (fstat(fd_, &st) != 0 || st.st_size < 4) {
        if (err) *err = "empty or unreadable PCM file: " + path;
        close();
        return false;
    }
    mapSize_ = (size_t)st.st_size;
    map_ = mmap(nullptr, mapSize_, PROT_READ, MAP_SHARED, fd_, 0);
    if (map_ == MAP_FAILED) {
        map_ = nullptr;
        if (err) *err = "mmap " + path + ": " + std::strerror(errno);
        close();
        return false;
    }
    data_ = static_cast<const int16_t*>(map_);
    frames_ = (int64_t)(mapSize_ / 4);
    // Small files: ask for everything up front.
    madvise(map_, std::min<size_t>(mapSize_, 8u << 20), MADV_WILLNEED);
    return true;
}

void PcmSource::close() {
    if (map_) munmap(map_, mapSize_);
    if (fd_ >= 0) ::close(fd_);
    map_ = nullptr;
    data_ = nullptr;
    fd_ = -1;
    mapSize_ = 0;
    frames_ = 0;
}

void PcmSource::prefetch(int64_t startFrame, int64_t count) const {
    if (!map_) return;
    startFrame = std::clamp<int64_t>(startFrame, 0, frames_);
    int64_t end = std::clamp<int64_t>(startFrame + count, 0, frames_);
    if (end <= startFrame) return;
    const long page = sysconf(_SC_PAGESIZE);
    uintptr_t base = reinterpret_cast<uintptr_t>(map_);
    uintptr_t a = base + (uintptr_t)startFrame * 4;
    uintptr_t b = base + (uintptr_t)end * 4;
    a &= ~(uintptr_t)(page - 1);
    madvise(reinterpret_cast<void*>(a), b - a, MADV_WILLNEED);
    // Touch one byte per page so the pages are actually resident before the
    // audio thread needs them (WILLNEED is only advisory).
    volatile uint8_t sink = 0;
    for (uintptr_t p = a; p < b; p += (uintptr_t)page) sink ^= *reinterpret_cast<const uint8_t*>(p);
    (void)sink;
}

}  // namespace phase
