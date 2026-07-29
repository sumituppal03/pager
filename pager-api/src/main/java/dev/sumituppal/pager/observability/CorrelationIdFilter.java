package dev.sumituppal.pager.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that ensures every request has a correlation ID and that
 * the ID is available to logging via SLF4J's {@link MDC}.
 *
 * <p>Behavior:
 * <ol>
 *   <li>If the incoming request carries a valid {@code X-Correlation-Id}
 *       header, that value is respected. This lets upstream services
 *       (load balancers, ingress gateways, calling apps) propagate their
 *       trace ID through us.</li>
 *   <li>Otherwise a fresh ID is generated.</li>
 *   <li>The ID is placed in the SLF4J MDC under key {@link #MDC_KEY} so
 *       Logback can inject it into every log line via {@code %X{correlationId}}.</li>
 *   <li>The ID is echoed on the response so callers can log it too.</li>
 *   <li>The MDC entry is <strong>always</strong> cleared in a {@code finally}
 *       block. Thread pools reuse threads across requests — a leaked MDC
 *       entry attaches request A's ID to request B's logs, which is a real
 *       production incident I've seen more than once.</li>
 * </ol>
 *
 * <p>Ordered first ({@link Ordered#HIGHEST_PRECEDENCE}) so all subsequent
 * filters, controllers, and error handlers see the correlation ID.
 *
 * <p>Async support (workers processing on separate threads) is not solved
 * by this filter alone — that's an intentional out-of-scope call, punted
 * to the worker PR where it belongs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String inbound = request.getHeader(HEADER_NAME);
        String correlationId = CorrelationIdGenerator.isValid(inbound)
                ? inbound
                : CorrelationIdGenerator.generate();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Critical: clear MDC so the next request on this thread starts clean.
            MDC.remove(MDC_KEY);
        }
    }
}
