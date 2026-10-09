#include "latest_frame_mailbox.h"
#include <array>
#include <cassert>
#include <future>
#include <cstdio>
struct Frame { std::array<int, 8> bins{}; int center = 0, time = 0; };
int main() {
    LatestFrameMailbox<Frame> queue;
    queue.reset();
    for (int n = 1; n <= 1000; ++n) queue.submit([&](Frame& frame) { frame.bins.fill(n); frame.center = n*10; frame.time = n*20; });
    Frame received;
    assert(queue.take(received));
    assert(received.bins[0] == 1000 && received.bins[7] == 1000 && received.center == 10000 && received.time == 20000);
    assert(queue.replaced() == 999);
    // The consumer copy remains owned even when the producer replaces its pending buffer.
    queue.submit([](Frame& frame) { frame.bins.fill(1); });
    assert(received.bins[0] == 1000);
    queue.stop(); assert(!queue.take(received));
    queue.reset();
    auto blocked = std::async(std::launch::async, [&] { Frame frame; return queue.take(frame); });
    queue.stop(); assert(!blocked.get());
    queue.reset(); queue.submit([](Frame& frame) { frame.center = 42; });
    assert(queue.take(received) && received.center == 42 && queue.replaced() == 0);
    std::puts("PASS: latest frame wins, coherent metadata, owned consumer copy, cancellation and restart");
}
