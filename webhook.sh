#!/bin/bash
# Send a signed webhook to the local Pager API for demo purposes.

SECRET="dev-only-change-me"

# PagerDuty V3 webhook envelope — Java parses event.data.{id, title, service, severity}.
BODY='{"event":{"id":"01EVENT-DEMO-001","event_type":"incident.triggered","occurred_at":"2025-08-14T07:00:00Z","data":{"id":"PGR-DEMO-001","type":"incident","title":"checkout-service 5xx spike (demo)","service":{"summary":"checkout-api"},"urgency":"high","severity":"P1"}}}'

SIG=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')

echo "Signature: $SIG"

curl -sS -X POST http://localhost:8080/webhooks/pagerduty \
  -H "Content-Type: application/json" \
  -H "X-PagerDuty-Signature: v1=$SIG" \
  -d "$BODY" \
  -w "\nHTTP Status: %{http_code}\n"