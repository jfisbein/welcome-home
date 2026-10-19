package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import welcomehome.audit.AccessOutcome;

class LockActionsTests {

    final LockActions lockActions = new LockActions();

    @Test
    void guestsWhoArriveDuringAnActionWaitForItAndShareItsResult() throws Exception {
        var lockId = UUID.randomUUID();
        var actions = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(5)) {
            var first = executor.submit(() -> lockActions.runOnce(lockId, () -> {
                actions.incrementAndGet();
                started.countDown();
                await(release);
                return AccessOutcome.NUKI_ERROR;
            }));
            started.await(5, TimeUnit.SECONDS);
            var others = new ArrayList<Future<AccessOutcome>>();
            for (int i = 0; i < 4; i++) {
                others.add(executor.submit(() -> lockActions.runOnce(lockId, () -> {
                    actions.incrementAndGet();
                    return AccessOutcome.OPENED;
                })));
            }
            Thread.sleep(200);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.NUKI_ERROR);
            for (var other : others) {
                assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.NUKI_ERROR);
            }
        }
        assertThat(actions).hasValue(1);
    }

    @Test
    void anActionAfterTheFirstEndedRunsAgain() {
        var lockId = UUID.randomUUID();
        var actions = new AtomicInteger();

        lockActions.runOnce(lockId, () -> {
            actions.incrementAndGet();
            return AccessOutcome.OPENED;
        });
        lockActions.runOnce(lockId, () -> {
            actions.incrementAndGet();
            return AccessOutcome.OPENED;
        });

        assertThat(actions).hasValue(2);
    }

    @Test
    void differentLocksDoNotWaitForEachOther() throws Exception {
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocked = executor.submit(() -> lockActions.runOnce(UUID.randomUUID(), () -> {
                await(release);
                return AccessOutcome.OPENED;
            }));

            assertThat(lockActions.runOnce(UUID.randomUUID(), () -> AccessOutcome.EXPIRED)).isEqualTo(AccessOutcome.EXPIRED);

            release.countDown();
            assertThat(blocked.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.OPENED);
        }
    }

    @Test
    void aFailedActionDoesNotBlockTheLock() {
        var lockId = UUID.randomUUID();

        assertThatThrownBy(() -> lockActions.runOnce(lockId, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(lockActions.runOnce(lockId, () -> AccessOutcome.OPENED)).isEqualTo(AccessOutcome.OPENED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
