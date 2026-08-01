package dev.sumituppal.pager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sumituppal.pager.ingress.TriageJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis wiring.
 *
 * <p>Spring Boot autoconfigures a generic {@code RedisTemplate<Object, Object>}
 * that serializes with JDK serialization — which is fragile (any class
 * rename breaks it), non-portable (a Python worker couldn't read it), and
 * bloated. We build a typed template that serializes keys as strings and
 * values as JSON.
 *
 * <p>The bean is named {@code triageQueueTemplate} explicitly — the
 * producer injects it by name, and if someone later adds a second Redis
 * template for a different purpose, there's no ambiguity.
 */
@Configuration
public class RedisConfig {

    /**
     * Typed template for pushing {@link TriageJob} onto the triage work queue.
     *
     * <p>Keys are strings (the queue name from {@code PagerProperties}),
     * values are TriageJob records serialized as JSON via Jackson.
     */
    @Bean(name = "triageQueueTemplate")
    public RedisTemplate<String, TriageJob> triageQueueTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {

        RedisTemplate<String, TriageJob> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys are always strings — the queue name.
        template.setKeySerializer(new StringRedisSerializer());

        // Values are JSON. Reuse the app-wide ObjectMapper so any custom
        // configuration (like the record-parameter-name settings) applies here too.
        Jackson2JsonRedisSerializer<TriageJob> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, TriageJob.class);
        template.setValueSerializer(valueSerializer);

        // Sensible defaults for hash operations too, in case anyone uses them later.
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}