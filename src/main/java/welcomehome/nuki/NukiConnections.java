package welcomehome.nuki;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException.Reason;

/**
 * Owners' Nuki connections: an API token is stored only after Nuki accepts it, encrypted for its owner,
 * and decrypted only to call Nuki.
 */
@Service
class NukiConnections {

    /** Far longer than any real token: longer input is rejected without calling Nuki. */
    private static final int MAX_TOKEN_LENGTH = 1000;

    private final NukiWebClient nuki;
    private final NukiConnectionDao connections;
    private final CryptoService crypto;
    private final OwnerTransactions ownerTransactions;

    NukiConnections(NukiWebClient nuki, NukiConnectionDao connections, CryptoService crypto,
                    OwnerTransactions ownerTransactions) {
        this.nuki = nuki;
        this.connections = connections;
        this.crypto = crypto;
        this.ownerTransactions = ownerTransactions;
    }

    /**
     * Checks the token with Nuki and, if Nuki accepts it, stores it as the owner's connection.
     *
     * @return the smart locks that the token gives access to
     * @throws LockProviderException with {@link Reason#ACCESS_DENIED} if Nuki rejects the token or it cannot
     *                               be one, or {@link Reason#UNAVAILABLE} if Nuki does not answer
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

    Optional<NukiConnection> find(UUID ownerId) {
        return ownerTransactions.execute(ownerId, () -> connections.find(ownerId))
                .map(stored -> new NukiConnection(stored.status(), stored.lastCheckedAt()));
    }

    /**
     * The owner's API token, to call Nuki with.
     *
     * @throws LockProviderException with {@link Reason#NOT_CONNECTED} if the owner has no working connection
     */
    String apiToken(UUID ownerId) {
        var stored = ownerTransactions.execute(ownerId, () -> connections.find(ownerId))
                .filter(connection -> connection.status() == ConnectionStatus.OK)
                .orElseThrow(() -> new LockProviderException(Reason.NOT_CONNECTED));
        try {
            return crypto.decryptApiToken(ownerId, stored.apiTokenEncrypted());
        } catch (DecryptionFailedException e) {
            // Lost key or a token that is not this owner's: the owner has to paste it again.
            throw new LockProviderException(Reason.NOT_CONNECTED, e);
        }
    }
}
