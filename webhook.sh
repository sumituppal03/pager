#!/bin/bash
SECRET="dev-only-change-me"
BODY='{"event":{"id":"01EVENT-RAG-002","event_type":"incident.triggered","occurred_at":"2025-08-15T10:00:00Z","data":{"id":"PGR-RAG-002","type":"incident","title":"checkout-service returning 5xx errors after deploy","service":{"summary":"checkout-service"},"urgency":"high","severity":"P1"}}}'
SIG=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')
echo "Signature: $SIG"
curl -sS -X POST http://localhost:8080/webhooks/pagerduty \
  -H "Content-Type: application/json" \
  -H "X-PagerDuty-Signature: v1=$SIG" \
  -d "$BODY" \
  -w "\nHTTP Status: %{http_code}\n"
