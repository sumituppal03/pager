package dev.sumituppal.pager;

import dev.sumituppal.pager.worker.TriageWorker;
import dev.sumituppal.pager.worker.TriageOrchestrator;
import dev.sumituppal.pager.domain.TriageRunRepository;
import dev.sumituppal.pager.observability.CorrelationIdFilter;
import dev.sumituppal.pager.observability.CorrelationIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests.
 *
 * <p>Proves that the Spring ApplicationContext loads, the health endpoint
 * responds, and the correlation-ID filter stamps every response.
 *
 * <p>We exclude JPA + Flyway + Redis autoconfigure so this can run in CI
 * without any external services. But we still need bean stubs for the
 * few components ({@link WebhookIngressService}, {@link TriageQueueProducer})
 * that depend on {@link TriageRunRepository} and {@link RedisTemplate} —
 * hence the {@link MockBean} declarations below.
 *
 * <p>Integration tests that actually exercise those beans use Testcontainers
 * and live in the {@code domain} package.
 *
 * <p>TODO(post-MVP): Split this into {@code @WebMvcTest} slices for the
 * filter and a proper {@code @SpringBootTest} for the full ingress path.
 * Right now the goal is minimum ceremony to keep this smoke test running
 * as we add more beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
                })
@ActiveProfiles("test")
class PagerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    /**
     * JPA autoconfigure is excluded, so no repository beans get created.
     * WebhookIngressService needs this to be constructable — provide a mock.
     */
    @MockBean
    private TriageRunRepository triageRunRepository;

    @MockBean
    private TriageWorker triageWorker;

    @MockBean
    private TriageOrchestrator triageOrchestrator;

    /**
     * Redis autoconfigure is excluded, so no RedisTemplate beans get created.
     * TriageQueueProducer needs a RedisTemplate<String, TriageJob> — provide a mock.
     * The raw type is intentional: MockBean's type-based matching finds it
     * regardless of the generic parameters at injection sites.
     */
    @MockBean(name = "triageQueueTemplate")
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    @Test
    @DisplayName("Spring context loads")
    void contextLoads() {
        assertThat(context.getBeanDefinitionCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("/actuator/health responds UP")
    void healthEndpointIsUp() {
        var response = rest.getForEntity(
            "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Every response carries a generated X-Correlation-Id when none is sent")
    void correlationIdIsGeneratedWhenAbsent() {
        var response = rest.getForEntity(
            "http://localhost:" + port + "/actuator/health", String.class);

        String correlationId = response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME);
        assertThat(correlationId).isNotBlank();
        assertThat(CorrelationIdGenerator.isValid(correlationId))
            .as("response header must be a valid correlation ID, got '%s'", correlationId)
            .isTrue();
    }

    @Test
    @DisplayName("A valid inbound X-Correlation-Id round-trips unchanged")
    void correlationIdRoundTrips() {
        String inbound = "req_TestId12345"; // valid format
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER_NAME, inbound);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        var response = rest.exchange(
            "http://localhost:" + port + "/actuator/health",
            HttpMethod.GET,
            requestEntity,
            String.class);

        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME))
            .isEqualTo(inbound);
    }
}