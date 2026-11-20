package welcomehome.auth;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
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

    /**
     * Also what Spring Security's own endpoint stores, which checks nothing of a passkey's name: stripped, cut to
     * what the app's own forms allow, and never blank, as it is shown on the passkeys page and in emails.
     */
    static String storedLabel(@Nullable String label) {
        var stripped = label == null ? "" : label.strip();
        if (stripped.isEmpty()) {
            return OwnerRegistration.DEFAULT_PASSKEY_LABEL;
        }
        return stripped.length() <= PasskeysController.MAX_LABEL_LENGTH ? stripped
                : stripped.substring(0, PasskeysController.MAX_LABEL_LENGTH).strip();
    }

    @Override
    public void save(CredentialRecord given) {
        var label = storedLabel(given.getLabel());
        var credentialRecord = label.equals(given.getLabel()) ? given
                : ImmutableCredentialRecord.fromCredentialRecord(given).label(label).build();
        var added = credentials.findByCredentialId(credentialRecord.getCredentialId()) == null
                && !credentials.findByUserId(credentialRecord.getUserEntityUserId()).isEmpty();
        credentials.save(credentialRecord);
        if (added) {
            var userEntity = userEntities.findById(credentialRecord.getUserEntityUserId());
            if (userEntity != null) {
                OwnerName.ownerId(userEntity.getName()).ifPresent(ownerId ->
                        events.publishEvent(new PasskeyAdded(ownerId, credentialRecord.getLabel())));
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
