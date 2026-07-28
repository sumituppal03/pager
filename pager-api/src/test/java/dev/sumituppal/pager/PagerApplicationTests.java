package dev.sumituppal.pager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The smoke test.
 *
 * A test at this level exists for one reason: to prove that the
 * Spring ApplicationContext loads. If this fails, nothing else works.
 * It's the cheapest signal you can get that PR #01 is green.
 *
 * We disable JPA + Flyway + Redis in the test profile so this can
 * pass without any external services — you can run `mvn test` on a
 * plane with no network.
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
    void contextLoads() {
        // The presence of any bean proves the context wired up.
        assertThat(context.getBeanDefinitionCount()).isGreaterThan(0);
    }

    @Test
    void healthEndpointIsUp() {
        // /actuator/health should respond 200 with status UP even before we
        // wire persistence. Docker's healthcheck depends on this.
        var response = rest.getForEntity(
            "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
