package welcomehome.nuki;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.NukiConnectionDao.StoredConnection;

/**
 * Owners' Nuki connections: an API token the owner pasted, or the tokens of an OAuth2 authorization. They are
 * stored only after Nuki accepts them, encrypted for their owner, and decrypted only to call Nuki. An OAuth2
 * access token is refreshed before it expires. When Nuki rejects a stored token, the connection becomes
 * {@link ConnectionStatus#DISCONNECTED} until the owner connects Nuki again.
 */
@Service
public class NukiConnections {

    /** Far longer than any real token: longer input is rejected without calling Nuki. */
    private static final int MAX_TOKEN_LENGTH = 1000;

    /** An access token this close to expiring is refreshed first, so that it does not expire during a call. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final NukiWebClient nuki;
    private final NukiConnectionDao connections;
    private final CryptoService crypto;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;

    /**
     * One refresh at a time per owner: Nuki rotates the refresh token, so a second refresh with the same one
     * would fail. Kept in memory, which is enough for a single instance.
     */
    private final ConcurrentMap<UUID, ReentrantLock> refreshes = new ConcurrentHashMap<>();

    NukiConnections(NukiWebClient nuki, NukiConnectionDao connections, CryptoService crypto,
                    OwnerTransactions ownerTransactions, ApplicationEventPublisher events) {
        this.nuki = nuki;
        this.connections = connections;
        this.crypto = crypto;
        this.ownerTransactions = ownerTransactions;
        this.events = events;
    }

    /**
     * Finds an owner's Nuki connection.
     *
     * @param ownerId the owner
     * @return the connection, or empty if the owner has never connected Nuki
     */
    public Optional<NukiConnection> find(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> connections.find(ownerId))
                .map(stored -> new NukiConnection(stored.id(), stored.credential(), stored.status(),
                        stored.lastCheckedAt()));
    }

    /**
     * Checks the token with Nuki and, if Nuki accepts it, stores it as the owner's connection.
     *
     * @return the smart locks that the token gives access to
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki rejects the token or it cannot
     *                               be one, {@link Reason#MISSING_PERMISSION} if it may not list locks or read
     *                               their log, or
     *                               {@link Reason#NUKI_ERROR} if Nuki does not answer
     */
    List<SmartLock> connect(UUID ownerId, String apiToken) {
        var token = apiToken.strip();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH || token.chars().anyMatch(Character::isWhitespace)) {
            throw new LockProviderException(Reason.ACCESS_DENIED);
        }
        var locks = checkAccess(token);
        var encrypted = crypto.encryptAccessToken(ownerId, token);
        ownerTransactions.run(ownerId, () -> connections.save(ownerId, NukiCredential.API_TOKEN, encrypted, null,
                null));
        return locks;
    }

    /**
     * Exchanges the code that Nuki sent back with the owner for the owner's tokens, checks them as
     * {@link #connect(UUID, String)} checks an API token, and stores them as the owner's connection.
     *
     * @param codeVerifier the PKCE verifier whose challenge went to Nuki with the owner
     * @param redirectUri  where Nuki sent the owner back, which the exchange repeats
     * @return the smart locks that the authorization gives access to
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki refuses the code,
     *                               {@link Reason#MISSING_PERMISSION} if the owner did not grant everything
     *                               welcome-home asked for, or {@link Reason#NUKI_ERROR} if Nuki does not answer
     */
    List<SmartLock> connectAuthorized(UUID ownerId, String code, String codeVerifier, URI redirectUri) {
        var tokens = notActing(() -> nuki.exchangeCode(code, codeVerifier, redirectUri));
        var refreshToken = tokens.refreshToken();
        if (refreshToken == null || !tokens.grantsAll(NukiWebClient.SCOPES)) {
            throw new LockProviderException(Reason.MISSING_PERMISSION);
        }
        var locks = checkAccess(tokens.accessToken());
        var accessTokenEncrypted = crypto.encryptAccessToken(ownerId, tokens.accessToken());
        var refreshTokenEncrypted = crypto.encryptRefreshToken(ownerId, refreshToken);
        var expiresAt = expiresAt(tokens);
        ownerTransactions.run(ownerId, () -> connections.save(ownerId, NukiCredential.OAUTH2, accessTokenEncrypted,
                refreshTokenEncrypted, expiresAt));
        return locks;
    }

    /** Outside a transaction: no database connection is held while waiting for Nuki. */
    private List<SmartLock> checkAccess(String accessToken) {
        var locks = nuki.listLocks(accessToken);
        // Openings are confirmed from the lock's log: without reading it, no opening would ever be confirmed.
        nuki.checkLogAccess(accessToken);
        return locks;
    }

    /**
     * Deletes the owner's connection and its tokens. Locks and links stay as they are, but cannot open the door
     * until the owner connects Nuki again.
     *
     * @return how the deleted connection was made, or empty if the owner had none
     */
    Optional<NukiCredential> remove(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> connections.delete(ownerId));
    }

    /**
     * Calls Nuki with the owner's stored access token, refreshing it first if it is about to expire. If Nuki
     * rejects the token or the refresh, the connection is disconnected, unless the owner has connected Nuki
     * again in the meantime.
     *
     * @throws LockProviderException with {@link Reason#NOT_CONNECTED} if the owner has no working connection,
     *                               or whatever the call or the refresh throws; a refresh that fails throws
     *                               {@link Reason#NUKI_ERROR} at most, as the call was not made
     */
    <T extends @Nullable Object> T callNuki(UUID ownerId, Function<String, T> call) {
        var stored = connected(ownerId);
        if (stored.credential() == NukiCredential.OAUTH2 && expiresSoon(stored)) {
            stored = refresh(ownerId);
        }
        var accessTokenEncrypted = stored.accessTokenEncrypted();
        var accessToken = decrypted(() -> crypto.decryptAccessToken(ownerId, accessTokenEncrypted));
        try {
            return call.apply(accessToken);
        } catch (LockProviderException e) {
            if (e.reason() == Reason.ACCESS_DENIED) {
                disconnect(ownerId, accessTokenEncrypted);
            }
            throw e;
        }
    }

    private StoredConnection connected(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> connections.find(ownerId))
                .filter(connection -> connection.status() == ConnectionStatus.OK)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
    }

    /**
     * Refreshes the owner's access token, unless another call did while this one waited for its turn.
     *
     * @return the connection with the access token to use
     */
    private StoredConnection refresh(UUID ownerId) {
        var lock = refreshes.computeIfAbsent(ownerId, _ -> new ReentrantLock());
        lock.lock();
        try {
            var stored = connected(ownerId);
            var refreshTokenEncrypted = stored.refreshTokenEncrypted();
            if (stored.credential() != NukiCredential.OAUTH2 || refreshTokenEncrypted == null || !expiresSoon(stored)) {
                return stored;
            }
            var refreshToken = decrypted(() -> crypto.decryptRefreshToken(ownerId, refreshTokenEncrypted));
            NukiTokens tokens;
            try {
                tokens = notActing(() -> nuki.refresh(refreshToken));
            } catch (LockProviderException e) {
                if (e.reason() == Reason.ACCESS_DENIED) {
                    disconnect(ownerId, stored.accessTokenEncrypted());
                }
                throw e;
            }
            // Nuki rotates the refresh token, but one that is not returned stays valid (RFC 6749, section 6).
            var newRefreshToken = tokens.refreshToken() == null ? refreshToken : tokens.refreshToken();
            var accessTokenEncrypted = crypto.encryptAccessToken(ownerId, tokens.accessToken());
            var newRefreshTokenEncrypted = crypto.encryptRefreshToken(ownerId, newRefreshToken);
            var expiresAt = expiresAt(tokens);
            var saved = ownerTransactions.execute(ownerId, () -> connections.saveRefreshed(ownerId,
                    stored.accessTokenEncrypted(), accessTokenEncrypted, newRefreshTokenEncrypted, expiresAt));
            return saved ? connected(ownerId) : stored;
        } finally {
            lock.unlock();
        }
    }

    private static String decrypted(Supplier<String> decryption) {
        try {
            return decryption.get();
        } catch (DecryptionFailedException e) {
            // Lost key or a token that is not this owner's: the owner has to connect again.
            throw new LockProviderException(Reason.NOT_CONNECTED, e);
        }
    }

    private void disconnect(UUID ownerId, byte[] rejectedAccessTokenEncrypted) {
        ownerTransactions.run(ownerId, () -> {
            if (connections.markDisconnected(ownerId, rejectedAccessTokenEncrypted)) {
                events.publishEvent(new NukiDisconnected(ownerId));
            }
        });
    }

    private static boolean expiresSoon(StoredConnection stored) {
        var expiresAt = stored.accessTokenExpiresAt();
        return expiresAt == null || !Instant.now().plus(REFRESH_MARGIN).isBefore(expiresAt);
    }

    /** From when the tokens arrived, by this server's clock, which may differ from Nuki's. */
    private static Instant expiresAt(NukiTokens tokens) {
        return Instant.now().plus(tokens.expiresIn());
    }

    /**
     * Runs a token request, which acts on no lock: a {@link Reason#NO_ANSWER} becomes {@link Reason#NUKI_ERROR},
     * so that no caller takes it for an action that may have run.
     */
    private static NukiTokens notActing(Supplier<NukiTokens> request) {
        try {
            return request.get();
        } catch (LockProviderException e) {
            if (e.reason() == Reason.NO_ANSWER) {
                throw new LockProviderException(Reason.NUKI_ERROR, e);
            }
            throw e;
        }
    }

    /**
     * Checks that Nuki still accepts the owner's stored token, so that the owner learns about a revoked
     * token before a guest does. An OAuth2 access token about to expire is refreshed, which keeps the refresh
     * token from expiring while the app runs.
     *
     * @return the connection's status after the check, or empty if the owner has no connection to check
     * @throws LockProviderException if Nuki could not tell, for example because it did not answer
     */
    Optional<ConnectionStatus> check(UUID ownerId) {
        try {
            callNuki(ownerId, accessToken -> {
                nuki.listLocks(accessToken);
                return null;
            });
        } catch (LockProviderException e) {
            return switch (e.reason()) {
                case NOT_CONNECTED -> Optional.empty();
                case ACCESS_DENIED -> Optional.of(ConnectionStatus.DISCONNECTED);
                default -> throw e;
            };
        }
        ownerTransactions.run(ownerId, () -> connections.markChecked(ownerId));
        return Optional.of(ConnectionStatus.OK);
    }

    List<UUID> ownersToCheck() {
        return connections.allOwnerIds();
    }
}
