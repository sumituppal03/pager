package dev.sumituppal.pager.ingress;

import dev.sumituppal.pager.ingress.WebhookIngressService.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives PagerDuty webhook POSTs.
 *
 * <p>The controller does no business logic — it consumes the raw body
 * as {@code byte[]} (critical for HMAC verification, which must run
 * against the exact bytes the sender signed), hands off to
 * {@link WebhookIngressService}, and translates the service's
 * {@link Result} into an HTTP response.
 *
 * <p>Deliberately: we accept {@code byte[]} rather than a parsed DTO
 * at the controller level. If Spring parses the body into a DTO first,
 * we'd have to re-serialize it to bytes for HMAC — and the re-serialized
 * form doesn't byte-match the original, so the signature never verifies.
 * Always take raw bytes at the boundary of any signature-verifying handler.
 */
@RestController
@RequestMapping("/webhooks/pagerduty")
public class PagerDutyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PagerDutyWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-PagerDuty-Signature";

    private final WebhookIngressService service;

    public PagerDutyWebhookController(WebhookIngressService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        Result result = service.process(rawBody, signature);

        return switch (result) {
            case Result.SignatureInvalid ignored -> ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid signature"));

            case Result.MalformedPayload m -> ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "malformed payload", "reason", m.reason()));

            case Result.Duplicate d -> ResponseEntity
                    .status(HttpStatus.OK)
                    .body(Map.of(
                            "status", "duplicate",
                            "triageId", d.triageId()));

            case Result.Accepted a -> ResponseEntity
                    .status(HttpStatus.ACCEPTED)  // 202 — request accepted, processing async
                    .body(Map.of(
                            "status", "accepted",
                            "triageId", a.triageId()));
        };
    }
}