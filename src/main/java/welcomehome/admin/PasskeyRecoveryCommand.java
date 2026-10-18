package welcomehome.admin;

import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code --recover-owner=<email>} command: creates a passkey recovery link for that owner, prints it and
 * exits. It is how an admin who lost their only passkey gets back in; other owners can also get one from the
 * admin panel.
 */
@Component
class PasskeyRecoveryCommand implements ApplicationRunner {

    static final String OPTION = "recover-owner";

    private final InvitationService invitations;
    private final OwnerDao owners;
    private final ApplicationContext context;

    PasskeyRecoveryCommand(InvitationService invitations, OwnerDao owners, ApplicationContext context) {
        this.invitations = invitations;
        this.owners = owners;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        var values = args.getOptionValues(OPTION);
        var email = values == null || values.size() != 1 ? "" : values.getFirst();
        var recovery = createRecovery(email);
        recovery.ifPresentOrElse(
                created -> System.out.printf("Passkey recovery link for %s, single use, valid until %s:%n%s%n",
                        email, created.expiresAt(), created.url()),
                () -> System.err.println("No owner with the email '" + email + "': use --recover-owner=<email>."));
        int exitCode = recovery.isPresent() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    Optional<CreatedInvitation> createRecovery(String email) {
        return owners.findIdByEmail(email.strip().toLowerCase(Locale.ROOT))
                .map(ownerId -> invitations.createPasskeyRecovery(null, ownerId));
    }
}
