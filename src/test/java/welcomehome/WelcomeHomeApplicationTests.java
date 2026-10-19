package welcomehome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
@AutoConfigureMockMvc
class WelcomeHomeApplicationTests {

    @Autowired
    MockMvcTester mvc;

    /** The executor behind @Async, which sends the emails. */
    @Autowired
    @Qualifier("applicationTaskExecutor")
    Executor applicationTaskExecutor;

    @Test
    void healthEndpointIsUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void asyncWorkRunsOnVirtualThreads() throws Exception {
        var virtual = new CompletableFuture<Boolean>();

        applicationTaskExecutor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()));

        assertThat(virtual.get(5, TimeUnit.SECONDS)).isTrue();
    }
}
