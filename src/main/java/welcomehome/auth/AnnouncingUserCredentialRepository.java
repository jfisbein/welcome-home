package welcomehome.auth;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * Spring Security's passkey store, publishing a {@link PasskeyAdded} when a passkey is added to an owner who
 * already had one, whichever way it was added: from {@code /settings/passkeys} through Spring Security's endpoints, or
 * with a recovery link. A new owner's first passkey is no news to anyone, and signing in saves an existing
 * passkey again, to update it.
 */
class AnnouncingUserCredentialRepository implements UserCredentialRepository {

    private final UserCredentialRepository credentials;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final ApplicationEventPublisher events;

    AnnouncingUserCredentialRepository(UserCredentialRepository credentials,
                                       PublicKeyCredentialUserEntityRepository userEntities,
                                       ApplicationEventPublisher events) {
        this.credentials = credentials;
        this.userEntities = userEntities;
        this.events = events;
    }

    @Override
    public void save(CredentialRecord credentialRecord) {
        var added = credentials.findByCredentialId(credentialRecord.getCredentialId()) == null
                && !credentials.findByUserId(credentialRecord.getUserEntityUserId()).isEmpty();
        credentials.save(credentialRecord);
        if (added) {
            var userEntity = userEntities.findById(credentialRecord.getUserEntityUserId());
            if (userEntity != null) {
                events.publishEvent(new PasskeyAdded(userEntity.getName(), credentialRecord.getLabel()));
            }
        }
    }

    @Override
    public void delete(Bytes credentialId) {
        credentials.delete(credentialId);
    }

    @Override
    public @Nullable CredentialRecord findByCredentialId(Bytes credentialId) {
        return credentials.findByCredentialId(credentialId);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return credentials.findByUserId(userId);
    }
}
