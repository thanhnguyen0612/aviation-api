# Aviation API Wrapper

A small Java 21 / Spring Boot 4 microservice that returns airport details for an ICAO code, backed by the public [aviationweather.gov](https://aviationweather.gov/data/api/) API. Built for the Sporty Group backend take-home; the focus is resilience, extensibility, observability and security rather than feature count.

`GET /api/v1/airports/{icao}` returns a clean, provider-neutral JSON shape (no runways or frequencies, no upstream field names).

## Run

Requires Java 21 (Maven is bundled as `./mvnw`).

```bash
# Maven
./mvnw spring-boot:run

# Docker
docker build -t aviation-api .
docker run --rm -p 8080:8080 --name aviation-api aviation-api
```

The service listens on `http://localhost:8080`. The upstream base URL is configurable via `aviation-weather.base-url`.

## Example requests

```bash
curl -s localhost:8080/api/v1/airports/KJFK
```
```json
{"icao":"KJFK","iata":"JFK","name":"NEW YORK/JOHN F KENNEDY INTL","region":"NY","country":"US","latitude":40.6399,"longitude":-73.7787,"elevationMeters":4}
```

```bash
curl -si localhost:8080/api/v1/airports/ZZZZ      # unknown code
```
```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"Airport not found: ZZZZ","instance":"/api/v1/airports/ZZZZ","status":404,"title":"Airport not found"}
```

```bash
curl -si localhost:8080/api/v1/airports/ABC       # not 4 alphanumeric characters
```
```
HTTP/1.1 400
Content-Type: application/problem+json

{"detail":"Validation failure","instance":"/api/v1/airports/ABC","status":400,"title":"Bad Request"}
```

Lowercase input works (`kjfk`); it is normalised to upper case and shares the cache entry with `KJFK`. `region` is the upstream `state` field, which also holds region codes for non-US airports.

## Running tests

```bash
./mvnw test
```

| Test | Covers |
|---|---|
| `AirportApiIntegrationTest` | Full stack against WireMock standing in for aviationweather.gov: 200 mapping, 404 (not cached), 400 with zero upstream calls, upstream outage to 503, cache hit incl. lowercase input |
| `RateLimiterIntegrationTest` | Exceeding the outbound quota gives 503 + `Retry-After` and no further upstream call |
| `ActuatorIntegrationTest` | `/actuator/health` UP, breaker metrics exposed, `/actuator/env` not exposed |
| `AviationWeatherAirportProviderTest` | Upstream mapping and failure translation (204/`[]` empty, malformed body, null fields, 4xx/5xx) |
| `AviationWeatherCircuitBreakerTest` | Breaker opens after repeated failures, including mid-retry |
| `AirportControllerTest`, `GetAirportByIcaoUseCaseTest` | Input validation and error mapping; use case normalisation and not-found |

## Assumptions

Upstream facts verified against the live API on 2026-11-14:

- `GET /api/data/airport?ids={ICAO}&format=json` returns HTTP 200 with a JSON **array**, even for one id.
- An unknown ICAO returns **204 with an empty body**, not 404. A malformed id returns 400. Lowercase ids work.
- Documented limit: **100 requests/minute**, and exceeding it may get access blocked. Documented errors: 400, 403, 404, 429, 500, 502, 503, 504.
- `elev` is in metres. `name` is upper case, sometimes with trailing whitespace (trimmed). `iataId` is not always present; missing values become `null`.

Decisions:

- ICAO codes only (4 alphanumeric characters); no IATA or batch lookup.
- The upstream is treated as unstable: timeouts, retries, breaker, outbound rate limit, cache.
- 403 is treated like 429 (likely "access blocked").

## Architecture

Pragmatic Clean Architecture. One rule: **dependencies point inward** (adapters, then application, then domain).

```
com.sporty.aviation
├── domain/                        pure Java, no framework imports
│   ├── Airport                        entity (record), provider-neutral
│   ├── AirportNotFoundException
│   └── ProviderUnavailableException   optional retryAfter
├── application/                   depends only on domain
│   ├── GetAirportByIcaoUseCase        normalises ICAO, @Cacheable, empty -> not found
│   └── port/AirportProvider           output port
├── adapter/
│   ├── in/web/                    driving adapter
│   │   ├── AirportController          validates path variable, maps to DTO
│   │   ├── AirportResponse            API DTO
│   │   └── GlobalExceptionHandler     domain exceptions -> ProblemDetail
│   └── out/aviationweather/       driven adapter, implements AirportProvider
│       ├── AviationWeatherAirportProvider   RestClient + Resilience4j + status handling
│       └── AviationWeatherAirport           mirrors upstream JSON (fields we use)
└── config/                        RestClient, cache and properties wiring
```

- Only `adapter/out/aviationweather` knows the upstream URL, field names and quirks (e.g. 204 means empty). Swapping or adding a provider means implementing `AirportProvider`.
- The cache sits on the use case, so it is provider-agnostic and a hit costs no rate-limit permit and never touches the breaker.
- **Trade-off:** `application/` uses `@Service` and `@Cacheable` by choice, to keep the code small. The strict alternative is a plain use-case class wired in `config/`, with a caching decorator around the `AirportProvider` port. `domain/` stays framework-free either way.
- No extra ceremony: one use case, one port, no input-port interface or presenters.
- Notable dependencies: `spring-boot-starter-restclient` (needed in Boot 4 for `RestClient.Builder`), `spring-boot-starter-aspectj` (Resilience4j annotations), Caffeine, `resilience4j-spring-boot4`.

## Resilience and error handling

All errors are RFC 9457 `ProblemDetail` (`application/problem+json`) from a single `@RestControllerAdvice`. Upstream bodies and stack traces are never returned.

| Situation | Status |
|---|---|
| `icao` does not match `^[A-Za-z0-9]{4}$` (rejected before any upstream call) | 400 |
| Upstream 204 or empty array | 404 |
| Upstream timeout, I/O error, 5xx, 429, 403, malformed body, or circuit open | 503 |
| Our outbound rate limiter rejects the call | 503 + `Retry-After` (seconds) |
| Any other unexpected upstream 4xx (a bug on our side) | 500 |
| Anything unexpected | 500 |

Settings (`application.yml`, instance `aviationWeather`):

| Mechanism | Setting |
|---|---|
| Timeouts | connect 2s, read 3s |
| Retry | 3 attempts, 200ms base, x2 exponential; only 5xx and I/O errors, never 4xx or 429 |
| Circuit breaker | count-based window 10, min 5 calls, 50% failure threshold, open 30s, 3 half-open calls; records 5xx, I/O errors, 429, 403 only (not-found is not a failure) |
| Rate limiter | 90 permits per minute (headroom under upstream's 100), no wait |
| Cache | Caffeine `maximumSize=10000,expireAfterWrite=1h,recordStats`; successful lookups only (TTL matches upstream `max-age=3600`) |
| Fallback | clean 503 after retries or when the breaker is open; serving stale cache is a next step |

**Aspect order:** Resilience4j's default, Retry wraps CircuitBreaker wraps RateLimiter wraps the call.

- Each retry attempt consumes a rate-limiter permit, so retries cannot push us past the upstream limit.
- During an outage the breaker can open mid-retry and block the remaining attempts (covered by `AviationWeatherCircuitBreakerTest`).
- A rate-limiter rejection is not retried and is not recorded as a breaker failure, since the upstream was never called.

**Caveat:** the rate limiter is **per instance**. Several instances need a distributed limiter (e.g. Redis-backed) or a lower per-instance quota.

## Observability

- SLF4J logs: one INFO line per upstream lookup (`icao`, `outcome`, `latencyMs`), WARN on failures and fallbacks, ERROR with stack trace only for unexpected errors. No request bodies or secrets.
- Actuator exposes only `health`, `info`, `metrics`:
  - `/actuator/health` includes the circuit breaker health indicator.
  - `/actuator/metrics` includes `resilience4j.circuitbreaker.*`, retry and rate-limiter metrics, and Caffeine cache metrics (`cache.gets`, etc.).
- Consistent `ProblemDetail` error bodies.

## Security

- Strict path-variable validation before any outbound call (also prevents injecting into the upstream query).
- Upstream errors are never echoed; `server.error.include-stacktrace` and `include-message` are `never`.
- Outbound HTTPS only; base URL comes from configuration.
- Docker image runs as a non-root user.
- Actuator exposure limited to `health,info,metrics`.

## What I'd do next

- OpenAPI / Swagger UI.
- Inbound rate limiting and authentication / API keys.
- Redis cache plus a distributed rate limiter for multi-instance deployments.
- Serve stale cache when the upstream is down (instead of 503).
- Multiple providers behind `AirportProvider`, with fallback between them.
- Prometheus registry and dashboards; batch lookup.

## AI usage

I used Claude Code (Anthropic) to brainstorm the design, write the spec and implementation plan (in `docs/superpowers/`), and generate the code and tests through subagents, with a review step after each task. I reviewed, ran and verified everything, and can explain all of it.
