package welcomehome.nuki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * Owners' Nuki connections: an API token is stored only after Nuki accepts it, encrypted for its owner,
 * and decrypted only to call Nuki. When Nuki rejects a stored token, the connection becomes
 * {@link ConnectionStatus#DISCONNECTED} until the owner pastes a new one.
 */
@Service
public class NukiConnections {

    /** Far longer than any real token: longer input is rejected without calling Nuki. */
    private static final int MAX_TOKEN_LENGTH = 1000;

    private final NukiWebClient nuki;
    private final NukiConnectionDao connections;
    private final CryptoService crypto;
    private final OwnerTransactions ownerTransactions;
    private final ApplicationEventPublisher events;

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
                .map(stored -> new NukiConnection(stored.id(), stored.status(), stored.lastCheckedAt()));
    }

    /**
     * Checks the token with Nuki and, if Nuki accepts it, stores it as the owner's connection.
     *
     * @return the smart locks that the token gives access to
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki rejects the token or it cannot
     *                               be one, {@link Reason#MISSING_PERMISSION} if it may not list locks, or
     *                               {@link Reason#NUKI_ERROR} if Nuki does not answer
     */
    List<SmartLock> connect(UUID ownerId, String apiToken) {
        var token = apiToken.strip();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH || token.chars().anyMatch(Character::isWhitespace)) {
            throw new LockProviderException(Reason.ACCESS_DENIED);
        }
        // Outside the transaction: no database connection is held while waiting for Nuki.
        var locks = nuki.listLocks(token);
        var encrypted = crypto.encryptApiToken(ownerId, token);
        ownerTransactions.run(ownerId, () -> connections.save(ownerId, encrypted));
        return locks;
    }

    /**
     * Deletes the owner's connection and its API token. Locks and links stay as they are, but cannot open
     * the door until the owner connects Nuki again.
     */
    void remove(UUID ownerId) {
        ownerTransactions.run(ownerId, () -> connections.delete(ownerId));
    }

    /**
     * Calls Nuki with the owner's stored API token. If Nuki rejects the token, the connection is
     * disconnected, unless the owner has stored another token in the meantime.
     *
     * @throws LockProviderException with {@link Reason#NOT_CONNECTED} if the owner has no working connection,
     *                               or whatever the call throws
     */
    <T extends @Nullable Object> T callNuki(UUID ownerId, Function<String, T> call) {
        var stored = ownerTransactions.execute(ownerId, () -> connections.find(ownerId))
                .filter(connection -> connection.status() == ConnectionStatus.OK)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
        String apiToken;
        try {
            apiToken = crypto.decryptApiToken(ownerId, stored.apiTokenEncrypted());
        } catch (DecryptionFailedException e) {
            // Lost key or a token that is not this owner's: the owner has to paste it again.
            throw new LockProviderException(Reason.NOT_CONNECTED, e);
        }
        try {
            return call.apply(apiToken);
        } catch (LockProviderException e) {
            if (e.reason() == Reason.ACCESS_DENIED) {
                ownerTransactions.run(ownerId, () -> {
                    if (connections.markDisconnected(ownerId, stored.apiTokenEncrypted())) {
                        events.publishEvent(new NukiDisconnected(ownerId));
                    }
                });
            }
            throw e;
        }
    }

    /**
     * Checks that Nuki still accepts the owner's stored token, so that the owner learns about a revoked
     * token before a guest does.
     *
     * @return the connection's status after the check, or empty if the owner has no connection to check
     * @throws LockProviderException if Nuki could not tell, for example because it did not answer
     */
    Optional<ConnectionStatus> check(UUID ownerId) {
        try {
            callNuki(ownerId, apiToken -> {
                nuki.listLocks(apiToken);
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
