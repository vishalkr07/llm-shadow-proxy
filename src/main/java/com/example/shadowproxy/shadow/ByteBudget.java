package com.example.shadowproxy.shadow;

import java.util.concurrent.atomic.AtomicLong;

public final class ByteBudget {
    static final int JOB_OVERHEAD_BYTES = 512;

    private final long capacity;
    private final AtomicLong used = new AtomicLong();

    public ByteBudget(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean tryReserve(long bytes) {
        if (bytes <= 0 || bytes > capacity) {
            return false;
        }
        while (true) {
            long current = used.get();
            if (current > capacity - bytes) {
                return false;
            }
            if (used.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public void release(long bytes) {
        long remaining = used.addAndGet(-bytes);
        if (remaining < 0) {
            used.addAndGet(bytes);
            throw new IllegalStateException("released more bytes than were reserved");
        }
    }

    public long used() {
        return used.get();
    }

    public long capacity() {
        return capacity;
    }

    public static long reservationFor(ShadowJob job) {
        return (long) job.requestBody().length
                + job.primaryResponseBody().length
                + JOB_OVERHEAD_BYTES;
    }
}
