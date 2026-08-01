package com.example.shadowproxy.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ByteBudgetTest {

    @Test
    void neverExceedsCapacityUnderConcurrentReservationsAndReturnsToZero() throws Exception {
        ByteBudget budget = new ByteBudget(4096);
        AtomicLong maximumObserved = new AtomicLong();
        var executor = Executors.newFixedThreadPool(16);
        List<Callable<Void>> work = new ArrayList<>();

        for (int thread = 0; thread < 16; thread++) {
            work.add(
                    () -> {
                        for (int attempt = 0; attempt < 2_000; attempt++) {
                            if (budget.tryReserve(64)) {
                                maximumObserved.accumulateAndGet(budget.used(), Math::max);
                                budget.release(64);
                            }
                        }
                        return null;
                    });
        }

        executor.invokeAll(work);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maximumObserved.get()).isLessThanOrEqualTo(budget.capacity());
        assertThat(budget.used()).isZero();
    }

    @Test
    void rejectsAReservationLargerThanTheWholeBudget() {
        ByteBudget budget = new ByteBudget(128);

        assertThat(budget.tryReserve(129)).isFalse();
        assertThat(budget.used()).isZero();
    }
}
