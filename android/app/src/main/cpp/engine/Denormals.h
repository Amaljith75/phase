// Flush-to-zero setup for threads that run DSP.
#pragma once

#include <cstdint>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace phase {

// Flush denormals to zero on the audio thread: decaying reverb/delay tails
// otherwise produce subnormal floats, which are very slow on some CPUs.
inline void enableFlushToZero() {
    static thread_local bool done = false;
    if (done) return;
    done = true;
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    asm volatile("msr fpcr, %0" ::"r"(fpcr | (1ull << 24)));
#elif defined(__arm__)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    asm volatile("vmsr fpscr, %0" ::"r"(fpscr | (1u << 24)));
#elif defined(__x86_64__) || defined(__i386__)
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ | DAZ
#endif
}

}  // namespace phase
