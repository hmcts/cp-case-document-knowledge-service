# JWTFilter

`JWTFilter` enforces the presence of a JWT on incoming requests, validates it, and exposes user details for the lifetime of the request.

## Purpose
- Requires the `jwt` header on requests (except excluded paths)
- Validates and parses the token via `JWTService`
- Stores `userName` and `scope` in a request-scoped `AuthDetails` bean

## Configuration
Defined in `src/main/resources/application.yaml`:

```yaml
jwt:
  secretKey: "it-must-be-a-string-secret-at-least-256-bits-long"
  filter:
    enabled: false
```

- `jwt.secretKey`: Base64 key suitable for HS256 (≥ 256 bits)
- `jwt.filter.enabled`: When false, the filter is skipped entirely. When true, it runs for all paths except those excluded.

### Enabling per environment
- Env var: `JWT_FILTER_ENABLED=true`
- Tests: `@SpringBootTest(properties = "jwt.filter.enabled=true")`
- Profile override: `application-<profile>.yaml`

## Excluded paths
Currently excluded: `/health`. Extend in `JWTFilter.shouldNotFilter(...)` if needed.

## Error behaviour
- Missing header: 401 UNAUTHORIZED ("No jwt token passed")
- Invalid token: 400 BAD_REQUEST with details

## Related classes
- `uk.gov.hmcts.cp.filters.jwt.JWTFilter`
- `uk.gov.hmcts.cp.filters.jwt.JWTService`
- `uk.gov.hmcts.cp.filters.jwt.AuthDetails`
- `uk.gov.hmcts.cp.config.JWTConfig`
