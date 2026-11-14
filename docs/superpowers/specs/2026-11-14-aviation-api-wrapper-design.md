# Aviation API Wrapper — Design

Date: 2026-11-14
Source requirement: `V2_-_Aviation_API_Wrapper_-_Home_Assignment_Backend_Engineer_-_External.pdf` (Sporty Group, Backend Engineer take-home)

## 1. Intent and the why

A take-home assignment: a Java + Spring Boot microservice that returns airport details for an ICAO code, backed by the public aviationweather.gov API. The graders care more about engineering decisions than features: **scalability, resilience, extensibility, observability**, plus **security** (named in the overview).

The candidate will defend every line in a follow-up interview. So the overriding goal is: **small, readable, idiomatic code that visibly meets every grading point — and nothing more.** Each class should map to a requirement the candidate can name.

Target effort: the assignment suggests ~90 minutes; keep the scope in that spirit.

## 2. Goals, non-goals, anti-goals

**Goals**
- `GET` an airport by ICAO code, returning a clean, documented JSON shape.
- Graceful handling of upstream failure: timeouts, retries, circuit breaker, rate limits, fallback.
- Provider-agnostic core (the service does not know about aviationweather.gov).
- Basic observability: structured-ish logging, consistent error bodies, Actuator health + metrics.
- Executable via Maven and Docker.
- At least one integration test (against a faked upstream), plus focused unit tests.
- README covering everything the PDF lists (see §8).

**Non-goals** (documented in README as "would do next", not built)
- OpenAPI / Swagger UI.
- Rate limiting on our own inbound endpoint.
- Authentication / API keys.
- Distributed cache (Redis), multiple providers, batch lookup, any lookup other than ICAO.
- Frontend, user management.

**Anti-goals** (failure even if it works)
- Over-engineering: Clean Architecture ceremony beyond §6 (input-port interfaces, presenters, request models), generic frameworks, extra interfaces with a single implementation beyond the output port, custom resilience code that a library already does.
- Code the candidate can't explain line by line.
- Leaking upstream internals (raw error bodies, stack traces, upstream field names) to clients.
- Retrying things that can't succeed (400/404), or hammering the upstream past its rate limit.

## 3. Upstream facts (verified against the live API on 2026-11-14)

- Endpoint: `GET https://aviationweather.gov/api/data/airport?ids={ICAO}&format=json`
- Success: HTTP 200, body is a **JSON array** (even for one id). Relevant fields per element: `icaoId`, `iataId`, `faaId`, `name` (upper case, sometimes trailing whitespace), `state`, `country`, `lat`, `lon`, `elev`. Also runways, freqs and single-letter codes we ignore.
- `iataId` present for major airports; `faaId` is null outside the US.
- **Unknown ICAO → HTTP 204 with empty body** (not 404).
- Malformed id (e.g. `K!@`) → HTTP 400.
- Lowercase id works.
- Response header `cache-control: max-age=3600`.
- Docs: **all requests rate-limited to 100/minute; exceeding may get access blocked.** Documented errors: 400, 403, 404, 429, 500, 502, 503, 504.

## 4. Decided by the candidate

- **Stack:** Java + Spring Boot, Maven (later switched to Gradle with the Kotlin DSL at the candidate's request), Spring `RestClient`, Resilience4j, an in-memory cache, Spring Boot Actuator, WireMock for the integration test.
- **Response contains key info only** (per the PDF: "name, location, ICAO/IATA, etc."): no runways/frequencies.
- **Docker + Actuator are in**; OpenAPI, inbound rate limiting, auth are README "next steps".
- **Small and explainable over impressive.**
- **Clean Architecture, pragmatic variant** (Spring annotations allowed in `application/`, never in `domain/`). See §6.
- **Outbound rate limiter** in addition to the cache. See §7.

## 5. API contract

`GET /api/v1/airports/{icao}`

**200 OK**
```json
{
  "icao": "KJFK",
  "iata": "JFK",
  "name": "NEW YORK/JOHN F KENNEDY INTL",
  "region": "NY",
  "country": "US",
  "latitude": 40.6399,
  "longitude": -73.7787,
  "elevationMeters": 4
}
```
- `elev` upstream is metres (checked: KJFK 4 ≈ 13 ft, EGLL 26 ≈ 83 ft). `state` is a region code also for non-US airports (EGLL → `"EN"`), hence the neutral name `region`.
- `name` trimmed. Missing upstream values → `null`.
- Input is normalised to upper case before lookup; response `icao` is upper case.

**Errors** — all as RFC 7807 `ProblemDetail` (`application/problem+json`), via a single `@RestControllerAdvice`:

| Situation | Status | Notes |
|---|---|---|
| `icao` not matching `^[A-Za-z0-9]{4}$` | 400 | Rejected before any upstream call (input validation = first security layer; also prevents query injection into the upstream URL). |
| Upstream returns 204 / empty array | 404 | "Airport not found". |
| Upstream timeout, 5xx, 429, 403 (likely "access blocked"), connection error, or circuit open | 503 | Generic message; upstream detail logged, not returned. (My call: 403 treated like 429.) |
| Our own outbound rate limiter rejects the call | 503 | Includes `Retry-After` header (seconds). |
| Any other unexpected upstream 4xx | 500 | Indicates a bug on our side (we sent a bad request); logged at ERROR. |
| Anything unexpected | 500 | Generic message, logged with stack trace. |

## 6. Architecture

**Clean Architecture, pragmatic variant (candidate decided).** One rule: **dependencies point inward only** (adapters → application → domain). `domain/` is pure Java with no framework imports. `application/` may use Spring annotations (`@Service`, `@Cacheable`), a deliberate pragmatic trade-off the README states. No extra ceremony: a single use case and a single output port, no input-port interface, no interactor/presenter/request-model split.

Package root: `com.sporty.aviation` (my call).

```
domain/                          ← pure Java, depends on nothing
    Airport (record)                    — the entity, provider-neutral
    AirportNotFoundException
    ProviderUnavailableException        — carries optional retryAfter (for the rate-limiter case)
application/                     ← depends only on domain
    GetAirportByIcaoUseCase             — @Service; normalises ICAO to upper case, @Cacheable,
                                          calls the port, empty → AirportNotFoundException
    port/AirportProvider (interface)    — output port: Optional<Airport> findByIcao(String icao)
adapter/in/web/                  ← driving adapter
    AirportController                   — validates path var (regex), calls use case, maps to DTO
    AirportResponse (record)            — API DTO, mapped from Airport (entity never exposed directly)
    GlobalExceptionHandler              — @RestControllerAdvice: domain exceptions → ProblemDetail
adapter/out/aviationweather/     ← driven adapter, implements AirportProvider
    AviationWeatherAirportProvider      — RestClient call, Resilience4j annotations, status-code handling,
                                          maps upstream → Airport, translates failures → domain exceptions
    AviationWeatherAirport (record)     — mirrors the upstream JSON (only fields we use)
config/                          ← wiring
    RestClientConfig                    — base URL + connect/read timeouts from properties
    CacheConfig                         — Caffeine spec (TTL, max size)
```

Rules:
- Only `adapter/out/aviationweather` knows the upstream URL, field names, or status-code quirks (e.g. 204 → `Optional.empty()`). Resilience4j settings live with this adapter because they describe this specific upstream.
- The cache sits on the use case (provider-agnostic; a hit costs no rate-limit permit and never touches the breaker).
- Input format validation is a web-adapter concern; the use case assumes a well-formed code.
- Stateless apart from the in-memory cache; horizontally scalable as-is.
- README explains the layout, the dependency rule, and the pragmatic Spring-in-application choice (strict variant: plain class wired in `config/` + caching decorator around the port).

## 7. Resilience, observability, security

**Resilience (Resilience4j, configured in `application.yml`, instance name e.g. `aviationWeather`)**
- **Timeouts:** connect ~2s, read ~3s on the `RestClient` request factory.
- **Retry:** 3 attempts total, exponential backoff (~200ms base). Retry only on timeouts / I/O errors / 5xx. **Never** on 4xx (including 204-as-not-found, 400). 429 is not retried (retrying a rate limit makes it worse).
- **Circuit breaker:** count-based sliding window (~10 calls), ~50% failure threshold, ~30s open, a few half-open probes. Not-found does not count as a failure. Timeouts, I/O errors, 5xx, **429 and 403 are recorded as failures** (my call: if we're being throttled or blocked, the breaker should stop us calling). Other 4xx are ignored by the breaker.
- **Ordering:** keep Resilience4j's default aspect order (Retry outermost, wrapping CircuitBreaker), so each attempt is seen by the breaker and no order override is needed (my call: simplest to explain). README states the order.
- **Outbound rate limiter (candidate decided):** Resilience4j `RateLimiter` on the upstream call, ~90 permits/minute (headroom under the documented 100/min), short wait timeout (~0–500ms, builder's call). When it rejects, our caller gets **503 with a `Retry-After` header**. A rejection does not trigger retries and is not recorded as a circuit breaker failure (we never reached the upstream). With the default aspect order (Retry → CircuitBreaker → RateLimiter → call), each retry attempt also consumes a permit, so retries can't push us past the limit. README notes the limit is **per instance**; several instances would need a shared/distributed limiter (e.g. Redis-backed) or a lower per-instance quota.
- **Cache:** Caffeine via Spring Cache, TTL 1h (matches upstream `max-age`), bounded size (~10k entries). Caches successful lookups only. Primary defence against the 100 req/min upstream limit.
- **Fallback:** when the upstream call fails (after retries / circuit open), throw `ProviderUnavailableException` → 503. "Serve stale cache" is described in the README as a next step, not built (a plain TTL cache can't serve expired entries without extra machinery — my call to keep it small).
- 429 from upstream is logged at WARN and surfaces as 503 to our caller.

**Observability**
- SLF4J logging: INFO per upstream call outcome (icao, status, latency), WARN on retries/circuit transitions, ERROR on unexpected failures. No request bodies or secrets.
- Actuator: expose `health`, `info`, `metrics` (and `prometheus` if the micrometer registry is added — builder's call). Resilience4j circuit breaker health indicator and metrics enabled.
- Consistent `ProblemDetail` error bodies = error transparency.

**Security**
- Strict path-variable validation (regex) before any outbound call.
- Upstream errors never echoed to clients; no stack traces in responses (`server.error.include-stacktrace=never`).
- Outbound HTTPS only; base URL from config.
- Docker image runs as a non-root user.
- Actuator exposure limited to the endpoints above.

## 8. Testing and deliverables

**Tests**
- **Integration test (required):** `@SpringBootTest` + WireMock standing in for aviationweather.gov. Cases: 200 happy path (mapping incl. trimmed name), 204 → 404, 400 for invalid ICAO (and no upstream call made), upstream 500 → retried then 503, second call served from cache (upstream hit once).
- **Unit tests:** upstream → `Airport` mapping; exception handler mapping.
- Rate limiter rejection → 503 with `Retry-After` and no upstream call (e.g. via a test profile with a tiny limit, or a unit test of the handler).
- Optional: circuit breaker opens after repeated failures (builder's call if time allows).

**Deliverables**
- Maven project (`./mvnw spring-boot:run`, `./mvnw test`), multi-stage `Dockerfile`.
- README: setup/run (Maven + Docker), running tests, example `curl`s, assumptions (§3 facts, ICAO-only, unstable upstream), architecture (§6 diagram), error handling (§5 table, §7), "what I'd do next" (§2 non-goals + stale-cache fallback), and an **AI usage** section stating that Claude Code assisted with design and code, and that the candidate reviewed and verified everything.
- Public GitHub repo (candidate creates and pushes).

**Workflow (candidate's instruction):** work on a local git branch (no worktree); implement in subagent-driven mode.

## 9. Left to the builder

- Exact Spring Boot / Java versions: use a current supported Spring Boot release with which the Resilience4j Spring Boot starter is compatible (verify before choosing), Java 21.
- Exact timeout/retry/breaker numbers within the ranges above.
- Whether to use Resilience4j annotations or the programmatic decorators (annotations preferred for readability).
- Logging format details, test class naming, Dockerfile base image.
