# Aviation API Wrapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot microservice exposing `GET /api/v1/airports/{icao}` that returns airport details from aviationweather.gov, with timeouts, retry, circuit breaker, outbound rate limiting, caching and consistent error responses.

**Architecture:** Pragmatic Clean Architecture: `domain` (pure Java) ← `application` (one use case + one output port, may use Spring annotations) ← `adapter/in/web` and `adapter/out/aviationweather`, wired in `config`. Resilience lives on the outbound adapter; caching lives on the use case.

**Tech Stack:** Java 21, Spring Boot 4.0.9 (webmvc, validation, cache, actuator, aspectj), Resilience4j 2.4.0 (`resilience4j-spring-boot4`), Caffeine (Boot-managed), WireMock 3.13.2 (`wiremock-standalone`), JUnit 5, Mockito, Gradle wrapper (Kotlin DSL), Docker.

**Spec:** `docs/superpowers/specs/2026-11-14-aviation-api-wrapper-design.md`

## Global Constraints

- Java 21; Spring Boot `4.0.9`; `io.github.resilience4j:resilience4j-spring-boot4:2.4.0`; `org.wiremock:wiremock-standalone:3.13.2` (test scope); `com.github.ben-manes.caffeine:caffeine` (version managed by Boot).
- Boot 4 artifact names: `spring-boot-starter-webmvc`, `spring-boot-starter-aspectj` (not `-web` / `-aop`); test starters `spring-boot-starter-webmvc-test` etc. Boot 4 uses Jackson 3; Jackson *annotations* stay in `com.fasterxml.jackson.annotation`. Use `@MockitoBean` (not `@MockBean`).
- Package root `com.sporty.aviation`. `domain` has **no** Spring/Jackson imports.
- Upstream: `GET {baseUrl}/api/data/airport?ids={ICAO}&format=json`, base URL default `https://aviationweather.gov`.
- ICAO validation regex: `^[A-Za-z0-9]{4}$`; codes normalised to upper case.
- Timeouts: connect `2s`, read `3s`.
- Retry: 3 attempts, 200ms base, exponential ×2; only `HttpServerErrorException`, `ResourceAccessException`.
- Circuit breaker: COUNT_BASED, window 10, minimum calls 5, failure threshold 50%, open 30s, 3 half-open calls; records `HttpServerErrorException`, `ResourceAccessException`, `HttpClientErrorException$TooManyRequests`, `HttpClientErrorException$Forbidden` only.
- Rate limiter: 90 permits per `1m`, timeout `0`. Rejection → 503 + `Retry-After` (seconds = limit refresh period).
- Resilience4j default aspect order (Retry wraps CircuitBreaker wraps RateLimiter). Instance name for all three: `aviationWeather`.
- Cache name `airports`, Caffeine spec `maximumSize=10000,expireAfterWrite=1h`; only successful lookups cached.
- Errors are `ProblemDetail` (`application/problem+json`); never include upstream bodies or stack traces.
- Actuator exposes only `health,info,metrics`.
- Git: work on local branch `feature/aviation-api` (no worktree). Commit after every task.

## Review Focus

- Lowercase input (`kjfk`) → 200 with `"icao":"KJFK"`, and shares the cache entry with `KJFK` (test in Task 5).
- Upstream 200 with `[]` → 404, same as 204 (test in Task 4).
- Upstream 200 with malformed/non-JSON body → 503, not 500 and not a stack trace (test in Task 4).
- Upstream element with `null` name/iata/state → response fields `null`, no NPE from trimming (test in Task 4).
- Path values of length 3/5, with symbols, or with spaces → 400 and zero upstream calls (tests in Task 3 and Task 5).

---

### Task 1: Project scaffold and git branch

**Files:**
- Create (via Spring Initializr): `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `.gitattributes`, `src/main/java/com/sporty/aviation/AviationApiApplication.java`, `src/test/java/com/sporty/aviation/AviationApiApplicationTests.java`
- Create: `src/main/resources/application.yml` (replace generated `application.properties`), `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: buildable Gradle project; `test` profile file other tasks extend.

- [ ] **Step 1: Initialise git on `main` and commit the docs, then branch**

```bash
cd /Users/thanhnguyen/Source/Interview/sporty
git init -b main && git add docs && git commit -m "docs: add design spec and implementation plan"
git switch -c feature/aviation-api
```

- [ ] **Step 2: Generate the project into a temp dir and copy it in (Gradle is not installed locally; the wrapper comes from Initializr)**

```bash
T=$(mktemp -d) && curl -s https://start.spring.io/starter.zip \
  -d type=gradle-project-kotlin -d bootVersion=4.0.9 -d javaVersion=21 \
  -d groupId=com.sporty -d artifactId=aviation-api -d name=aviation-api \
  -d packageName=com.sporty.aviation -d dependencies=web,validation,cache,actuator \
  -o "$T/p.zip" && unzip -q "$T/p.zip" -d "$T/p" && rm "$T/p/HELP.md" && cp -R "$T/p/." .
```

- [ ] **Step 3: Add dependencies to `build.gradle.kts`**: `spring-boot-starter-aspectj`, `com.github.ben-manes.caffeine:caffeine`, `io.github.resilience4j:resilience4j-spring-boot4:2.4.0`; test scope `org.wiremock:wiremock-standalone:3.13.2`. Rename `src/main/resources/application.properties` → `application.yml` with `spring.application.name: aviation-api` only (later tasks add keys).

- [ ] **Step 4: Create `src/test/resources/application-test.yml`** (fast, deterministic resilience for tests):

```yaml
resilience4j:
  retry.instances.aviationWeather.wait-duration: 10ms
  ratelimiter.instances.aviationWeather.limit-for-period: 1000
  circuitbreaker.instances.aviationWeather.minimum-number-of-calls: 100
```
Annotate `AviationApiApplicationTests` with `@ActiveProfiles("test")`.

- [ ] **Step 5: Verify build**

Run: `./gradlew test`
Expected: BUILD SUCCESS, `contextLoads` passes.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "chore: scaffold Spring Boot 4 project with resilience4j and caffeine"
```

---

### Task 2: Domain and use case

**Files:**
- Create: `src/main/java/com/sporty/aviation/domain/Airport.java`, `domain/AirportNotFoundException.java`, `domain/ProviderUnavailableException.java`
- Create: `src/main/java/com/sporty/aviation/application/port/AirportProvider.java`, `application/GetAirportByIcaoUseCase.java`
- Test: `src/test/java/com/sporty/aviation/application/GetAirportByIcaoUseCaseTest.java`

**Interfaces:**
- Produces:
  - `record Airport(String icao, String iata, String name, String region, String country, Double latitude, Double longitude, Integer elevationMeters)`
  - `AirportNotFoundException extends RuntimeException` — ctor `(String icao)`, message `"Airport not found: " + icao`, getter `icao()`
  - `ProviderUnavailableException extends RuntimeException` — ctors `(String message, Throwable cause)` and `(String message, Throwable cause, Duration retryAfter)`; `Optional<Duration> retryAfter()`
  - `interface AirportProvider { Optional<Airport> findByIcao(String icao); }` — receives upper-case ICAO
  - `@Service class GetAirportByIcaoUseCase` — `Airport execute(String icao)`, annotated `@Cacheable(cacheNames = "airports", key = "#icao.toUpperCase()")`

- [ ] **Step 1: Write failing tests** (plain Mockito, no Spring context)

```java
@Test void returnsAirportFromProviderUsingUpperCaseCode() {
    when(provider.findByIcao("KJFK")).thenReturn(Optional.of(JFK));
    assertThat(useCase.execute("kjfk")).isEqualTo(JFK);
    verify(provider).findByIcao("KJFK");
}
@Test void throwsNotFoundWhenProviderReturnsEmpty() {
    when(provider.findByIcao("ZZZZ")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> useCase.execute("ZZZZ"))
        .isInstanceOf(AirportNotFoundException.class).hasMessage("Airport not found: ZZZZ");
}
@Test void propagatesProviderUnavailable() {
    when(provider.findByIcao("KJFK")).thenThrow(new ProviderUnavailableException("down", null));
    assertThatThrownBy(() -> useCase.execute("KJFK")).isInstanceOf(ProviderUnavailableException.class);
}
```
`JFK = new Airport("KJFK","JFK","NEW YORK/JOHN F KENNEDY INTL","NY","US",40.6399,-73.7787,4)`.

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests GetAirportByIcaoUseCaseTest` → compilation failure.
- [ ] **Step 3: Implement the five types above.** Constructor injection of `AirportProvider`; `execute` upper-cases with `Locale.ROOT`.
- [ ] **Step 4: Run tests** — same command → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: add airport domain, provider port and lookup use case"`

---

### Task 3: Web adapter (controller, DTO, error handling)

**Files:**
- Create: `src/main/java/com/sporty/aviation/adapter/in/web/AirportController.java`, `AirportResponse.java`, `GlobalExceptionHandler.java`
- Test: `src/test/java/com/sporty/aviation/adapter/in/web/AirportControllerTest.java`

**Interfaces:**
- Consumes: `GetAirportByIcaoUseCase.execute(String)`, `Airport`, both domain exceptions (Task 2).
- Produces:
  - `GET /api/v1/airports/{icao}` with `@PathVariable @Pattern(regexp = "^[A-Za-z0-9]{4}$") String icao` (Spring 7 built-in method validation; no `@Validated` needed)
  - `record AirportResponse(String icao, String iata, String name, String region, String country, Double latitude, Double longitude, Integer elevationMeters)` with `static AirportResponse from(Airport)`
  - `@RestControllerAdvice class GlobalExceptionHandler extends ResponseEntityExceptionHandler` (gives the 400 ProblemDetail for validation for free)

- [ ] **Step 1: Write failing `@WebMvcTest(AirportController.class)` tests** with `@MockitoBean GetAirportByIcaoUseCase`:
  - `returnsAirport` — 200; `$.icao=="KJFK"`, `$.iata=="JFK"`, `$.name`, `$.region=="NY"`, `$.country=="US"`, `$.latitude==40.6399`, `$.longitude==-73.7787`, `$.elevationMeters==4`.
  - `@ParameterizedTest @ValueSource(strings = {"KJF", "KJFKX", "K!@#", "KJ K"}) rejectsInvalidIcao` — 400, content type `application/problem+json`, `verifyNoInteractions(useCase)` (URL-encode the path segment).
  - `returns404WhenNotFound` — use case throws `AirportNotFoundException("ZZZZ")` → 404, `$.title=="Airport not found"`, `$.detail=="Airport not found: ZZZZ"`.
  - `returns503WhenProviderUnavailable` — throws `ProviderUnavailableException("x", cause)` → 503, `$.title=="Upstream provider unavailable"`, `$.detail=="Airport data is temporarily unavailable, please retry later"`, no `Retry-After` header.
  - `returns503WithRetryAfterWhenRateLimited` — exception with `retryAfter = Duration.ofSeconds(60)` → 503, header `Retry-After: 60`.
  - `returns500WithoutLeakingDetails` — throws `new IllegalStateException("secret upstream body")` → 500, `$.detail=="Unexpected error"`, response body does not contain `"secret"`.
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests AirportControllerTest` → FAIL.
- [ ] **Step 3: Implement controller, DTO and handler.** Handler: `@ExceptionHandler` methods returning `ResponseEntity<ProblemDetail>` (`ProblemDetail.forStatusAndDetail` + `setTitle`) for the two domain exceptions and `Exception` (log ERROR with stack trace for the latter; WARN for 503). Titles/details exactly as in the tests.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: add airport REST endpoint with ProblemDetail error handling"`

---

### Task 4: AviationWeather outbound adapter with resilience

**Files:**
- Create: `src/main/java/com/sporty/aviation/adapter/out/aviationweather/AviationWeatherAirport.java`, `AviationWeatherAirportProvider.java`
- Create: `src/main/java/com/sporty/aviation/config/AviationWeatherProperties.java`, `config/RestClientConfig.java`
- Modify: `src/main/resources/application.yml` (add `aviation-weather.*` and `resilience4j.*`)
- Test: `src/test/java/com/sporty/aviation/adapter/out/aviationweather/AviationWeatherAirportProviderTest.java`, `AviationWeatherCircuitBreakerTest.java`

**Interfaces:**
- Consumes: `AirportProvider`, `Airport`, `ProviderUnavailableException` (Task 2).
- Produces:
  - `@ConfigurationProperties("aviation-weather") record AviationWeatherProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout)`; enable with `@EnableConfigurationProperties` on `RestClientConfig`.
  - `@Bean RestClient aviationWeatherRestClient(RestClient.Builder, AviationWeatherProperties)` — `JdkClientHttpRequestFactory` over an `HttpClient` with connect timeout; `setReadTimeout(readTimeout)`.
  - `@JsonIgnoreProperties(ignoreUnknown = true) record AviationWeatherAirport(String icaoId, String iataId, String name, String state, String country, Double lat, Double lon, Integer elev)` with `Airport toDomain()` (trims `name` null-safely).
  - `@Component class AviationWeatherAirportProvider implements AirportProvider` — `findByIcao` annotated `@Retry(name = "aviationWeather", fallbackMethod = "fallback")`, `@CircuitBreaker(name = "aviationWeather")`, `@RateLimiter(name = "aviationWeather")`; `private Optional<Airport> fallback(String icao, Throwable t)`.

**Behaviour to implement:**
- Call `GET /api/data/airport?ids={icao}&format=json` with `.retrieve().body(AviationWeatherAirport[].class)`. `null` (204) or empty array → `Optional.empty()`; otherwise map element 0. Log INFO `icao`, outcome, latency ms.
- `fallback` (only on `@Retry`, the outermost aspect, so 5xx are retried before it runs). Log WARN with icao and exception class:
  - `RequestNotPermitted` → `ProviderUnavailableException` with `retryAfter` = `RateLimiterRegistry.rateLimiter("aviationWeather").getRateLimiterConfig().getLimitRefreshPeriod()`
  - `HttpClientErrorException` other than 429/403 → rethrow unchanged (→ 500 in Task 3's handler)
  - everything else (`CallNotPermittedException`, 5xx, 429, 403, `ResourceAccessException`, other `RestClientException` incl. bad JSON) → `ProviderUnavailableException("aviationweather.gov unavailable", t)`

`application.yml` additions (values from Global Constraints):
```yaml
aviation-weather:
  base-url: https://aviationweather.gov
  connect-timeout: 2s
  read-timeout: 3s
resilience4j:
  retry.instances.aviationWeather: { max-attempts: 3, wait-duration: 200ms, enable-exponential-backoff: true, exponential-backoff-multiplier: 2, retry-exceptions: [org.springframework.web.client.HttpServerErrorException, org.springframework.web.client.ResourceAccessException] }
  circuitbreaker.instances.aviationWeather: { sliding-window-type: COUNT_BASED, sliding-window-size: 10, minimum-number-of-calls: 5, failure-rate-threshold: 50, wait-duration-in-open-state: 30s, permitted-number-of-calls-in-half-open-state: 3, register-health-indicator: true, record-exceptions: [org.springframework.web.client.HttpServerErrorException, org.springframework.web.client.ResourceAccessException, "org.springframework.web.client.HttpClientErrorException$TooManyRequests", "org.springframework.web.client.HttpClientErrorException$Forbidden"] }
  ratelimiter.instances.aviationWeather: { limit-for-period: 90, limit-refresh-period: 1m, timeout-duration: 0 }
```
(Use block style in the file; flow style here for brevity.)

- [ ] **Step 1: Write failing tests** — `@SpringBootTest @ActiveProfiles("test")`, `@RegisterExtension static WireMockExtension wm` (dynamic port), `@DynamicPropertySource` sets `aviation-weather.base-url` to `wm.baseUrl()` and `aviation-weather.read-timeout` to `500ms`; `@BeforeEach` resets the `aviationWeather` circuit breaker via `CircuitBreakerRegistry`. Inject `AirportProvider`. Stub fixtures: the KJFK and EGLL JSON arrays from spec §3 (EGLL has `"faaId":null`).
  - `mapsSuccessfulResponse` — 200 KJFK fixture → `Airport("KJFK","JFK","NEW YORK/JOHN F KENNEDY INTL","NY","US",40.6399,-73.7787,4)` (trailing space trimmed); request had `ids=KJFK` and `format=json`.
  - `mapsNullFieldsWithoutError` — element with `"name":null,"iataId":null,"state":null` → those fields `null`.
  - `returnsEmptyOn204` and `returnsEmptyOnEmptyArray`.
  - `retriesServerErrorsThenThrowsUnavailable` — 500 → `ProviderUnavailableException`; `wm.verify(3, getRequestedFor(...))`.
  - `doesNotRetry429` / `doesNotRetry403` — → `ProviderUnavailableException`, exactly 1 request.
  - `rethrowsUnexpected4xx` — 400 → `HttpClientErrorException.BadRequest`, 1 request.
  - `timeoutIsRetriedThenUnavailable` — `withFixedDelay(1000)` → `ProviderUnavailableException`, 3 requests.
  - `malformedBodyIsUnavailable` — 200 with body `<html>oops</html>` and `Content-Type: application/json` → `ProviderUnavailableException`, 1 request.
  - `circuitOpensAfterRepeatedFailures` — separate class `AviationWeatherCircuitBreakerTest` (same setup) with `@TestPropertySource(properties = {"resilience4j.circuitbreaker.instances.aviationWeather.minimum-number-of-calls=2", "resilience4j.circuitbreaker.instances.aviationWeather.sliding-window-size=2"})`: stub 500. First call → `ProviderUnavailableException`, and the breaker state is `OPEN`. Exactly **2** upstream requests: the breaker sits inside the retry, so it opens after attempt 2 and blocks attempt 3. Second call → `ProviderUnavailableException` whose cause is `CallNotPermittedException`, and the request count is still 2.
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests 'AviationWeather*Test'` → FAIL.
- [ ] **Step 3: Implement properties, RestClient config, upstream record and provider** as described.
- [ ] **Step 4: Run tests** → PASS. Then `./gradlew test` → all PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: add aviationweather.gov adapter with timeouts, retry, circuit breaker and rate limiter"`

---

### Task 5: Caching and end-to-end integration tests

**Files:**
- Create: `src/main/java/com/sporty/aviation/config/CacheConfig.java` (`@Configuration @EnableCaching`, nothing else)
- Modify: `src/main/resources/application.yml` (`spring.cache.cache-names: airports`, `spring.cache.caffeine.spec: maximumSize=10000,expireAfterWrite=1h`)
- Test: `src/test/java/com/sporty/aviation/AirportApiIntegrationTest.java`, `src/test/java/com/sporty/aviation/RateLimiterIntegrationTest.java`

**Interfaces:**
- Consumes: the full app from Tasks 2–4. No new production types beyond `CacheConfig`.

- [ ] **Step 1: Write failing `AirportApiIntegrationTest`** — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, WireMock extension + `@DynamicPropertySource` as in Task 4, `@BeforeEach` clears the `airports` cache via `CacheManager` and resets the circuit breaker.
  - `returnsAirportJson` — stub KJFK → `GET /api/v1/airports/KJFK` 200 with all eight fields.
  - `secondLookupIsServedFromCacheIncludingLowercase` — `GET /kjfk` then `GET /KJFK` → both 200 with `$.icao=="KJFK"`; `wm.verify(1, ...)`.
  - `unknownAirportReturns404AndIsNotCached` — stub 204 → 404 problem+json twice; `wm.verify(2, ...)`.
  - `invalidIcaoReturns400WithoutUpstreamCall` — `GET /api/v1/airports/KJ` → 400; `wm.verify(0, anyRequestedFor(anyUrl()))`.
  - `upstreamOutageReturns503` — stub 503 → 503 problem+json, detail does not contain upstream body text.
- [ ] **Step 2: Write failing `RateLimiterIntegrationTest`** — same setup but `@TestPropertySource(properties = "resilience4j.ratelimiter.instances.aviationWeather.limit-for-period=1")`: stub KJFK and EGLL; `GET /KJFK` → 200; `GET /EGLL` → 503 with header `Retry-After: 60`; `wm.verify(0, getRequestedFor(urlPathEqualTo("/api/data/airport")).withQueryParam("ids", equalTo("EGLL")))`.
- [ ] **Step 3: Run to verify failure** — `./gradlew test --tests '*IntegrationTest'` → cache test FAILS (2 upstream calls).
- [ ] **Step 4: Add `CacheConfig` and cache properties.**
- [ ] **Step 5: Run full suite** — `./gradlew test` → all PASS.
- [ ] **Step 6: Commit** — `git commit -m "feat: cache airport lookups with Caffeine and add end-to-end integration tests"`

---

### Task 6: Observability config, Docker and README

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `Dockerfile`, `.dockerignore`, `README.md`
- Test: `src/test/java/com/sporty/aviation/ActuatorIntegrationTest.java`

- [ ] **Step 1: Write failing `ActuatorIntegrationTest`** (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`): `GET /actuator/health` → 200 `$.status=="UP"`; `GET /actuator/metrics` → 200 and `$.names` contains `resilience4j.circuitbreaker.state`; `GET /actuator/env` → 404.
- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests ActuatorIntegrationTest` → metrics assertion FAILS.
- [ ] **Step 3: Add to `application.yml`:** `management.endpoints.web.exposure.include: health,info,metrics`, `management.health.circuitbreakers.enabled: true`, `server.error.include-stacktrace: never`, `server.error.include-message: never`.
- [ ] **Step 4: Run** → PASS; `./gradlew test` → all PASS.
- [ ] **Step 5: Dockerfile** — multi-stage: `eclipse-temurin:21-jdk` runs `./gradlew bootJar`; runtime `eclipse-temurin:21-jre`, copies the jar, creates and switches to a non-root user, `EXPOSE 8080`, `ENTRYPOINT ["java","-jar","/app/app.jar"]`. `.dockerignore` excludes `target`, `.git`, `.idea`.
- [ ] **Step 6: Verify Docker** — `docker build -t aviation-api . && docker run -d --rm -p 8080:8080 --name aviation-api aviation-api`, then `curl -s localhost:8080/api/v1/airports/KJFK` returns the KJFK JSON and `curl -s localhost:8080/api/v1/airports/ZZZZ` returns a 404 problem; `docker stop aviation-api`.
- [ ] **Step 7: README.md** with sections: Overview; Run (Gradle `./gradlew bootRun`, Docker commands); Example requests (200, 404, 400 `curl`s with sample output); Running tests (`./gradlew test`, what the integration tests cover); Assumptions (upstream facts from spec §3, ICAO-only, unstable upstream); Architecture (package tree from spec §6, dependency rule, pragmatic Spring-in-application trade-off and the strict alternative); Resilience & error handling (status table from spec §5, retry/breaker/rate-limiter/cache settings, aspect order, per-instance rate-limit caveat); Observability (logs, actuator endpoints); Security (validation, no leaking, non-root image, limited actuator); What I'd do next (OpenAPI, inbound rate limiting, auth, Redis cache + distributed limiter, stale-cache fallback, multiple providers); AI usage (Claude Code assisted with design and code; the author reviewed, ran and verified everything).
- [ ] **Step 8: Commit** — `git commit -m "feat: expose actuator metrics, add Dockerfile and README"`
