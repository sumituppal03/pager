package dev.sumituppal.pager.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link CorrelationIdFilter}.
 *
 * <p>Uses Spring's {@code MockHttpServletRequest} / {@code MockHttpServletResponse}
 * so we can exercise the filter without booting a full Spring context. Fast,
 * focused, no autoconfigure surprises.
 */
class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        // Belt-and-braces: even if a test leaks MDC state, don't taint the next test.
        MDC.clear();
    }

    @Test
    @DisplayName("generates a fresh ID when no header is present")
    void generatesFreshIdWhenHeaderMissing() throws Exception {
        filter.doFilter(request, response, chain);

        String responseHeader = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(responseHeader).isNotBlank();
        assertThat(CorrelationIdGenerator.isValid(responseHeader)).isTrue();
    }

    @Test
    @DisplayName("respects a valid inbound X-Correlation-Id header")
    void respectsValidInboundHeader() throws Exception {
        String inbound = "req_TestId12345"; // 15 chars, matches format
        request.addHeader(CorrelationIdFilter.HEADER_NAME, inbound);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(inbound);
    }

    @Test
    @DisplayName("rejects a malformed inbound header and generates a fresh ID")
    void rejectsMalformedHeader() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "not a valid id");

        filter.doFilter(request, response, chain);

        String responseHeader = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(responseHeader).isNotEqualTo("not a valid id");
        assertThat(CorrelationIdGenerator.isValid(responseHeader)).isTrue();
    }

    @Test
    @DisplayName("sets the correlation ID in MDC during request handling")
    void setsMdcDuringRequest() throws Exception {
        String[] mdcDuringChain = new String[1];

        // Capture the MDC value at the moment the chain is invoked
        doAnswer(invocation -> {
            mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain[0]).isNotNull();
        assertThat(CorrelationIdGenerator.isValid(mdcDuringChain[0])).isTrue();
    }

    @Test
    @DisplayName("clears MDC after request completes")
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("clears MDC even when the filter chain throws")
    void clearsMdcOnException() throws Exception {
        doAnswer(invocation -> {
            throw new RuntimeException("simulated downstream failure");
        }).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // We want the exception to propagate; we're testing cleanup happened first.
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
            .as("MDC must be cleared even when the chain throws — thread reuse safety")
            .isNull();
    }

    @Test
    @DisplayName("actually invokes the filter chain exactly once")
    void invokesChainExactlyOnce() throws Exception {
        filter.doFilter(request, response, chain);
        verify(chain, times(1)).doFilter(any(), any());
    }
}

