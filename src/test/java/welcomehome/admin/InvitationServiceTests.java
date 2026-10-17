package welcomehome.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InvitationServiceTests {

    final InvitationDao invitations = mock(InvitationDao.class);

    @Test
    void doesNotStartWithoutABaseUrl() {
        assertThatThrownBy(() -> new InvitationService(invitations, ""))
                .hasMessage("No base URL configured: set BASE_URL");
    }

    @Test
    void buildsTheLinkWithoutADoubleSlash() {
        when(invitations.insert(any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new InvitationDao.InsertedInvitation(UUID.randomUUID(), OffsetDateTime.now()));
        var service = new InvitationService(invitations, "https://welcome.example/");

        var invitation = service.createInvitation(null, "Test", false);

        assertThat(invitation.url().toString()).startsWith("https://welcome.example/invite/");
    }
}
