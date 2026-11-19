package welcomehome.nuki;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException.Reason;
import welcomehome.nuki.NukiConnectionDao.StoredConnection;

/**
 * Renews an owner's stored tokens: an OAuth2 access token about to expire is exchanged, with its refresh token, for
 * new ones, and tokens that an earlier AEAD key encrypted are encrypted with the primary one. Both replace what is
 * stored, so they take turns per owner: Nuki rotates the refresh token, and a second refresh with the same one, or
 * one that read the tokens before another change, would leave a dead token stored. What to do with the new tokens,
 * and with a refusal, is {@link NukiConnections}'s.
 */
@Component
class TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);

    /** An access token this close to expiring is refreshed first, so that it does not expire during a call. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final NukiOAuthClient oauth;
    private final NukiConnectionDao connections;
    private final CryptoService crypto;
    private final OwnerTransactions ownerTransactions;

    /** One at a time per owner. Kept in memory, which is enough for a single instance. */
    private final ConcurrentMap<UUID, ReentrantLock> turns = new ConcurrentHashMap<>();
    private final Clock clock;

    TokenRefresher(NukiOAuthClient oauth, NukiConnectionDao connections, CryptoService crypto,
                   OwnerTransactions ownerTransactions, Clock clock) {
        this.clock = clock;
        this.oauth = oauth;
        this.connections = connections;
        this.crypto = crypto;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * The tokens a refresh returned, encrypted for their owner.
     *
     * @param accessTokenEncrypted  the new access token
     * @param refreshTokenEncrypted the refresh token to use next: Nuki's new one, or the one used if it sent none
     * @param accessTokenExpiresAt  when the access token expires
     */
    @SuppressWarnings("ArrayRecordComponent")
    record Refreshed(byte[] accessTokenEncrypted, byte[] refreshTokenEncrypted, Instant accessTokenExpiresAt) {
    }

    /** Runs work that reads and replaces an owner's stored tokens when no other such work runs for them. */
    <T> T inTurn(UUID ownerId, Supplier<T> work) {
        var turn = turns.computeIfAbsent(ownerId, _ -> new ReentrantLock());
        turn.lock();
        try {
            return work.get();
        } finally {
            turn.unlock();
        }
    }

    /**
     * Asks Nuki for new tokens with a stored refresh token; within the owner's turn. Storing them is the caller's.
     *
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki refuses the refresh token, or
     *                               {@link Reason#NUKI_ERROR} at most otherwise, as no lock was acted on
     */
    Refreshed refresh(UUID ownerId, byte[] refreshTokenEncrypted) {
        var refreshToken = decrypted(() -> crypto.decryptRefreshToken(ownerId, refreshTokenEncrypted));
        var tokens = notActing(() -> oauth.refresh(refreshToken));
        log.info("Nuki refreshed the tokens of owner {} with the scopes {}", ownerId, tokens.scopeForLog());
        // Nuki rotates the refresh token, but one that is not returned stays valid (RFC 6749, section 6).
        var newRefreshToken = tokens.refreshToken() == null ? refreshToken : tokens.refreshToken();
        return new Refreshed(crypto.encryptAccessToken(ownerId, tokens.accessToken()),
                crypto.encryptRefreshToken(ownerId, newRefreshToken), expiresAt(tokens));
    }

    /**
     * Encrypts the owner's stored tokens again with the AEAD keyset's primary key, where an earlier key encrypted
     * them, whatever the connection's status.
     *
     * @return how many of the owner's tokens were encrypted again, from 0 to 2
     * @throws DecryptionFailedException if a stored token does not decrypt, as when its key left the keyset
     */
    int reEncrypt(UUID ownerId) {
        return inTurn(ownerId, () -> {
            var found = ownerTransactions.execute(ownerId, () -> connections.find(ownerId));
            if (found.isEmpty()) {
                return 0;
            }
            var stored = found.get();
            var accessToken = stored.accessTokenEncrypted();
            var refreshToken = stored.refreshTokenEncrypted();
            var reEncryptedAccessToken = crypto.reEncryptAccessToken(ownerId, accessToken);
            var reEncryptedRefreshToken = refreshToken == null ? Optional.<byte[]>empty()
                    : crypto.reEncryptRefreshToken(ownerId, refreshToken);
            if (reEncryptedAccessToken.isEmpty() && reEncryptedRefreshToken.isEmpty()) {
                return 0;
            }
            var newAccessToken = reEncryptedAccessToken.orElse(accessToken);
            var newRefreshToken = reEncryptedRefreshToken.isPresent() ? reEncryptedRefreshToken.get() : refreshToken;
            var saved = ownerTransactions.execute(ownerId, () -> connections.saveReEncrypted(ownerId, accessToken,
                    refreshToken, newAccessToken, newRefreshToken));
            if (!saved) {
                return 0;
            }
            return (reEncryptedAccessToken.isPresent() ? 1 : 0) + (reEncryptedRefreshToken.isPresent() ? 1 : 0);
        });
    }

    boolean expiresSoon(StoredConnection stored) {
        var expiresAt = stored.accessTokenExpiresAt();
        return expiresAt == null || !clock.instant().plus(REFRESH_MARGIN).isBefore(expiresAt);
    }

    /** From when the tokens arrived, by this server's clock, which may differ from Nuki's. */
    Instant expiresAt(NukiTokens tokens) {
        return clock.instant().plus(tokens.expiresIn());
    }

    /**
     * Runs a token request, which acts on no lock: a {@link Reason#NO_ANSWER} becomes {@link Reason#NUKI_ERROR},
     * so that no caller takes it for an action that may have run.
     */
    static NukiTokens notActing(Supplier<NukiTokens> request) {
        try {
            return request.get();
        } catch (LockProviderException e) {
            if (e.reason() == Reason.NO_ANSWER) {
                throw new LockProviderException(Reason.NUKI_ERROR, e);
            }
            throw e;
        }
    }

    static String decrypted(Supplier<String> decryption) {
        try {
            return decryption.get();
        } catch (DecryptionFailedException e) {
            // Lost key or a token that is not this owner's: the owner has to connect again.
            throw new LockProviderException(Reason.NOT_CONNECTED, e);
        }
    }
}
