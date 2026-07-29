package dev.sumituppal.pager;

import dev.sumituppal.pager.observability.CorrelationIdFilter;
import dev.sumituppal.pager.observability.CorrelationIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests.
 *
 * <p>Proves that the Spring ApplicationContext loads, the health endpoint
 * responds, and — added in issue #2 — the correlation-ID filter stamps
 * every response with an X-Correlation-Id header.
 *
 * <p>We exclude JPA + Flyway + Redis autoconfigure so this can run in CI
 * without any external services. Integration tests that need those come
 * later, via Testcontainers.
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
