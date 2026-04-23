#!/usr/bin/env bash
# ─────────────────────────────────────────────────
#  run-local.sh
#  Verifies that WireMock is running and healthy
#  Triggers WireMock stubs using the Gradle task (runStubs)
#  Executes a curl command to start ingestion against the CDK service
# ─────────────────────────────────────────────────

# ── Config ────────────────────────────────────────
APP_BASE_URL="http://localhost:8082/casedocumentknowledge-service"
APP_TIMEOUT=30
WIREMOCK_PORT="8089"
STARTUP_TIMEOUT=30          # seconds to wait for WireMock

# ── Check WireMock is healthy ─────────────────────
echo "[stub] Checking WireMock health..."
elapsed=0
until curl -sf "http://localhost:${WIREMOCK_PORT}/__admin/health" > /dev/null 2>&1; do
  if (( elapsed >= STARTUP_TIMEOUT )); then
    echo "[error] WireMock not reachable after ${STARTUP_TIMEOUT}s. Is the container running?"
    exit 1
  fi
  sleep 1
  (( elapsed++ ))
done
echo "[stub] WireMock healthy after ${elapsed}s"

# ── Register stubs ────────────────────────────────
echo "[stub] Registering stubs..."
gradle runStubs -q 2>&1 | grep -i wiremock

# ── Check application is healthy ──────────────────
echo "[app] Checking application health..."
elapsed=0
until curl -sf --location \
  -H "Accept: application/json" \
  "${APP_BASE_URL}/actuator/health" > /dev/null 2>&1; do
  if (( elapsed >= APP_TIMEOUT )); then
    echo "[error] Application not reachable after ${APP_TIMEOUT}s. Is the service running?"
    exit 1
  fi
  sleep 1
  (( elapsed++ ))
done
echo "[app] Application healthy after ${elapsed}s"

# ── Fire curl against the app endpoint ────────────
echo "[curl] Calling app endpoint..."
HTTP_STATUS=$(curl -s --location \
  -o /tmp/response_body.json \
  -w "%{http_code}" \
  --request POST 'http://localhost:8082/casedocumentknowledge-service/ingestions/start' \
  --header 'CJSCPPUID: a085e359-6069-4694-8820-7810e7dfe762' \
  --header 'Content-Type: application/vnd.casedocumentknowledge-service.ingestion-process+json' \
  --data '{
      "courtCentreId": "f8254db1-1683-483e-afb3-b87fde5a0a26",
      "roomId": "b4562684-9209-3ec4-a544-7f80dabd94d8",
      "date": "2025-08-18"
  }')

# ── Report result ─────────────────────────────────
echo "[curl] HTTP status: $HTTP_STATUS"
echo "[curl] Response body:"
cat /tmp/response_body.json
echo

if [[ "$HTTP_STATUS" -ge 200 ]] && [[ "$HTTP_STATUS" -lt 300 ]]; then
  echo "[result] SUCCESS"
else
  echo "[result] FAILED with status $HTTP_STATUS"
  exit 1
fi