# Baseline: `GET /actuator/prometheus` whole-endpoint series count — pre-DD-43182

> Captured 2026-09-09, local `docker-compose.integration.yml` stack (`gradle bootJar -x test` +
> `docker compose -f docker/docker-compose.integration.yml up -d --build`), from commit `d81b9ed`
> (`develop` tip — DD-43182/DD-43183 design docs only, no implementation code) via `git stash push -u`
> of the in-progress implementation diff, immediately restored with `git stash pop` after capture.
> Per OQ-034 (decided 2026-09-04, `03-stories.md` Story 7): "pre-implementation" is a property of the
> **commit measured**, not the calendar date the capture happens on — this commit already contains
> DD-43185's own metrics (merged separately, unaffected by this ticket) but none of DD-43182's.

## How to regenerate this baseline

```bash
git stash push -u -m "temporarily remove DD-43182/DD-43183 implementation for baseline capture"
./gradlew bootJar -x test
export AZURE_STORAGE_CONNECTION_STRING="<the well-known Azurite emulator connection string used by every other integration-test run in this repo — see docker/docker-compose.integration.yml's AZURE_STORAGE_CONNECTION_STRING env passthrough; BlobEndpoint host must be the docker-network service name 'azurite', not 127.0.0.1>"
docker compose -f docker/docker-compose.integration.yml up -d --build
# wait for `docker inspect --format='{{.State.Health.Status}}' cdks_application` to report healthy
curl -s http://localhost:8082/casedocumentknowledge-service/actuator/prometheus -o /tmp/prometheus-baseline.txt
grep -vc '^#' /tmp/prometheus-baseline.txt   # total series
grep -c '^# TYPE' /tmp/prometheus-baseline.txt   # metric families
docker compose -f docker/docker-compose.integration.yml down -v
git stash pop
```

## Baseline measurement

| Metric | Value |
|---|---|
| Metric families (`# TYPE` lines) | **84** |
| Total series (non-comment, non-blank exposition lines) | **160** |
| Scrape wall-clock time (local compose, 5 runs) | ~0.01s each |

`84` families matches DD-43185's own post-implementation baseline (76 platform/Spring Boot families
+ 6 DD-43185 `cdk_*`/JobManager-adjacent families, plus scheduler/monitoring families it added — see
[`../DD-43185-stalled-work-scheduler-monitoring/baseline-actuator-prometheus.md`](../DD-43185-stalled-work-scheduler-monitoring/baseline-actuator-prometheus.md)),
with DD-43185's 14 series already present and counted in the `160` figure above.

## Expected post-implementation delta (this ticket)

Per `02-design.md` §9/§12 and ADR-011's amended arithmetic, DD-43182 adds:

- **7 rendered `cdk_*`/pool-binder metric names** (not 8 — `cdk_task_retry_exhausted_total` is
  withdrawn per ADR-011): `cdk_document_ingestion_phase_total`, `cdk_document_ingestion_duration_seconds`
  (+ `_count`/`_sum`/`_max`/`_bucket` sub-series from its SLO buckets), `cdk_external_call_duration_seconds`
  (+ sub-series), `cdk_task_retry_total`, `cdk_answer_generation_total`, plus the HTTP-pool binder's
  `httpcomponents.httpclient.pool.*` gauges (rendered as `httpcomponents_httpclient_pool_*` family
  names) and the `cdk_http_pool_connections_leased` alias gauge.
- **95 series pre-registered at construction** (worst case **232** once every dynamic outcome tag
  value has materialised at least once — see `02-design.md` §12's full accounting), an amendment down
  from 243/106 pre-ADR-011 (`cdk_task_retry_exhausted_total`'s 7 series and `outcome=timed_out`'s 4
  series both withdrawn).
- Combined with DD-43185's unchanged 14 series: **246 series worst case** once this ticket and
  DD-43185 are both fully warmed up in production, comfortably inside the 2,000-series budget
  (NFR-002) and the merge-blocking ceiling this story's `PrometheusSeriesBudgetHttpLiveTest` asserts
  against the compose stack (see that test for the exact ceiling value and its compose-vs-production
  reasoning).

Any *unexpected* removal, or a changed `# TYPE` on an existing family, is a regression against this
ticket's purely-additive design and should block the PR — the same NFR-005-style reasoning
DD-43185's own baseline file states.
