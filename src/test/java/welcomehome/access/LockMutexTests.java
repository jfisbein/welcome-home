package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import welcomehome.audit.AccessOutcome;

class LockMutexTests {

    final LockMutex lockMutex = new LockMutex();

    /**
     * The action is let go only once every other guest is seen waiting for it, each on its own thread: none of them
     * can then still be on its way to starting an action of its own.
     */
    @Test
    void guestsWhoArriveDuringAnActionWaitForItAndShareItsResult() throws Exception {
        var lockId = UUID.randomUUID();
        var actions = new AtomicInteger();
        var release = new CountDownLatch(1);
        var guests = new CopyOnWriteArrayList<Thread>();

        try (var executor = Executors.newFixedThreadPool(5, guest -> {
            var thread = new Thread(guest);
            guests.add(thread);
            return thread;
        })) {
            var first = executor.submit(() -> lockMutex.runOnce(lockId, () -> {
                actions.incrementAndGet();
                awaitRelease(release);
                return AccessOutcome.NUKI_ERROR;
            }));
            await().atMost(Duration.ofSeconds(5)).untilAtomic(actions, org.hamcrest.Matchers.equalTo(1));
            var others = new ArrayList<Future<AccessOutcome>>();
            for (int i = 0; i < 4; i++) {
                others.add(executor.submit(() -> lockMutex.runOnce(lockId, () -> {
                    actions.incrementAndGet();
                    return AccessOutcome.OPENED;
                })));
            }
            // The first waits for its release and the others for the first: all five, and none still running.
            await().atMost(Duration.ofSeconds(5)).until(() -> guests.size() == 5
                    && guests.stream().allMatch(guest -> guest.getState() == Thread.State.TIMED_WAITING));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.NUKI_ERROR);
            for (var other : others) {
                assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.NUKI_ERROR);
            }
        }
        assertThat(actions).hasValue(1);
    }

    /** A guest who waited is told what one who ran it is: here an error, never a result of their own. */
    @Test
    void aGuestWaitingForAnActionThatThrowsIsToldItFailedAndTheLockIsFreeAgain() throws Exception {
        var lockId = UUID.randomUUID();
        var release = new CountDownLatch(1);
        var guests = new CopyOnWriteArrayList<Thread>();

        try (var executor = Executors.newFixedThreadPool(2, guest -> {
            var thread = new Thread(guest);
            guests.add(thread);
            return thread;
        })) {
            var running = new AtomicInteger();
            var first = executor.submit(() -> lockMutex.runOnce(lockId, () -> {
                running.incrementAndGet();
                awaitRelease(release);
                throw new IllegalStateException("boom");
            }));
            await().atMost(Duration.ofSeconds(5)).untilAtomic(running, org.hamcrest.Matchers.equalTo(1));
            var waiting = executor.submit(() -> lockMutex.runOnce(lockId, () -> AccessOutcome.OPENED));
            await().atMost(Duration.ofSeconds(5)).until(() -> guests.size() == 2
                    && guests.stream().allMatch(guest -> guest.getState() == Thread.State.TIMED_WAITING));
            release.countDown();

            assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.NUKI_ERROR);
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(lockMutex.runOnce(lockId, () -> AccessOutcome.OPENED)).isEqualTo(AccessOutcome.OPENED);
    }

    @Test
    void anActionAfterTheFirstEndedRunsAgain() {
        var lockId = UUID.randomUUID();
        var actions = new AtomicInteger();

        lockMutex.runOnce(lockId, () -> {
            actions.incrementAndGet();
            return AccessOutcome.OPENED;
        });
        lockMutex.runOnce(lockId, () -> {
            actions.incrementAndGet();
            return AccessOutcome.OPENED;
        });

        assertThat(actions).hasValue(2);
    }

    @Test
    void differentLocksDoNotWaitForEachOther() throws Exception {
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocked = executor.submit(() -> lockMutex.runOnce(UUID.randomUUID(), () -> {
                awaitRelease(release);
                return AccessOutcome.OPENED;
            }));

            assertThat(lockMutex.runOnce(UUID.randomUUID(), () -> AccessOutcome.EXPIRED)).isEqualTo(AccessOutcome.EXPIRED);

            release.countDown();
            assertThat(blocked.get(5, TimeUnit.SECONDS)).isEqualTo(AccessOutcome.OPENED);
        }
    }

    @Test
    void aFailedActionDoesNotBlockTheLock() {
        var lockId = UUID.randomUUID();

        assertThatThrownBy(() -> lockMutex.runOnce(lockId, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(lockMutex.runOnce(lockId, () -> AccessOutcome.OPENED)).isEqualTo(AccessOutcome.OPENED);
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
