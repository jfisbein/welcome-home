package welcomehome.admin;

import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code --bootstrap-admin} command: creates the first admin's invitation, prints its link and exits.
 * It refuses when an admin already exists, so it cannot be used to take over a running installation.
 */
@Component
class BootstrapAdmin implements ApplicationRunner {

    static final String OPTION = "bootstrap-admin";

    private final InvitationService invitations;
    private final OwnerDao owners;
    private final ApplicationContext context;

    BootstrapAdmin(InvitationService invitations, OwnerDao owners, ApplicationContext context) {
        this.invitations = invitations;
        this.owners = owners;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        var invitation = createAdminInvitation();
        invitation.ifPresentOrElse(
                created -> System.out.printf("Admin invitation, single use, valid until %s:%n%s%n",
                        created.expiresAt(), created.url()),
                () -> System.err.println("An admin already exists: invite new owners from the admin panel."));
        int exitCode = invitation.isPresent() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    Optional<CreatedInvitation> createAdminInvitation() {
        if (owners.adminExists()) {
            return Optional.empty();
        }
        return Optional.of(invitations.createInvitation(null, "Bootstrap admin", true));
    }
}
