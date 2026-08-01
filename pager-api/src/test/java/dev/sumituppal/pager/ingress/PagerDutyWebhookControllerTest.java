package dev.sumituppal.pager.ingress;

import dev.sumituppal.pager.ingress.WebhookIngressService.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc-based tests for {@link PagerDutyWebhookController}.
 *
 * <p>Uses {@code @WebMvcTest} — the smallest slice that gives us the
 * Spring MVC infrastructure without the full app context. The
 * {@code WebhookIngressService} is mocked; we're only proving the
 * controller correctly translates each {@link Result} branch into
 * the right HTTP status + body.
 *
 * <p>This is layered testing done right: the service logic is proven
 * elsewhere (in {@link WebhookIngressServiceTest}); here we prove the
 * HTTP contract in isolation.
 */
@WebMvcTest(controllers = PagerDutyWebhookController.class)
class PagerDutyWebhookControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WebhookIngressService service;

    private static final String DUMMY_BODY = "{\"event\":{\"data\":{\"id\":\"P1\"}}}";

    @Test
    @DisplayName("returns 401 Unauthorized on SignatureInvalid")
    void signatureInvalidReturns401() throws Exception {
        when(service.process(any(), any())).thenReturn(new Result.SignatureInvalid());

        mvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-PagerDuty-Signature", "v1=bad")
                .content(DUMMY_BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid signature"));
    }

    @Test
    @DisplayName("returns 400 Bad Request on MalformedPayload")
    void malformedPayloadReturns400() throws Exception {
        when(service.process(any(), any()))
            .thenReturn(new Result.MalformedPayload("missing event.data.id"));

        mvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-PagerDuty-Signature", "v1=ok")
                .content(DUMMY_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("malformed payload"))
            .andExpect(jsonPath("$.reason").value("missing event.data.id"));
    }

    @Test
    @DisplayName("returns 200 OK with existing triage id on Duplicate")
    void duplicateReturns200() throws Exception {
        when(service.process(any(), any()))
            .thenReturn(new Result.Duplicate("triage_existing"));

        mvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-PagerDuty-Signature", "v1=ok")
                .content(DUMMY_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("duplicate"))
            .andExpect(jsonPath("$.triageId").value("triage_existing"));
    }

    @Test
    @DisplayName("returns 202 Accepted with new triage id on Accepted")
    void acceptedReturns202() throws Exception {
        when(service.process(any(), any()))
            .thenReturn(new Result.Accepted("triage_new"));

        mvc.perform(post("/webhooks/pagerduty")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-PagerDuty-Signature", "v1=ok")
                .content(DUMMY_BODY))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.triageId").value("triage_new"));
    }
}