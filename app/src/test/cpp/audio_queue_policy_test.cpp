#include "audio_queue_policy.h"
#include <cassert>
#include <cstdio>
int main() {
    assert(!audioQueueOverdue(11520, 0));
    assert(audioQueueOverdue(11522, 0));
    assert(!audioQueueOverdue(0x00001000, 0xfffff000));
    assert(audioQueueOverdue(0x00003000, 0xfffff000));
    assert(!audioQueueOverdue(0, 0));
    std::puts("PASS: 120 ms backlog limit, empty queue and sequence wrap");
}

