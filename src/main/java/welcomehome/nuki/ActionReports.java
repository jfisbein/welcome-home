package welcomehome.nuki;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

/**
 * The outcomes of actions that Nuki reports with a webhook, by the id of the request that sent each action. A
 * report may arrive before Nuki's answer to the request, which carries the id, so whichever comes first keeps a
 * place for the other. Kept in memory, which is enough for a single instance, and only for a few minutes: a report
 * arriving later is of no use, nor is one of a request this instance did not send, such as the development app's,
 * which shares the OAuth2 client and so the webhook URL.
 */
@Component
class ActionReports {

    /** Far longer than a guest waits for the confirmation. */
    static final Duration KEPT_FOR = Duration.ofMinutes(2);

    private final Cache<String, CompletableFuture<LockActionState>> outcomes = Caffeine.newBuilder()
            .expireAfterWrite(KEPT_FOR)
            .maximumSize(10_000)
            .build();

    /**
     * The action that Nuki accepted with the given request id.
     *
     * @param requestId the id that Nuki answered the request with
     * @return the action, whose outcome its report gives
     */
    PendingAction pending(String requestId) {
        var outcome = outcome(requestId);
        return timeout -> {
            try {
                return Optional.of(outcome.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException e) {
                throw new IllegalStateException("Reports are never completed exceptionally", e);
            }
        };
    }

    /**
     * Records the outcome that Nuki reported for a request.
     *
     * @param requestId the request's id
     * @param state     how the action ended
     */
    void report(String requestId, LockActionState state) {
        outcome(requestId).complete(state);
    }

    private CompletableFuture<LockActionState> outcome(String requestId) {
        return outcomes.get(requestId, _ -> new CompletableFuture<>());
    }
}
