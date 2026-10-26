# Expo Expo Server

Single-module Expo service built with Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, and Gradle. PostgreSQL with Flyway stores application data; local development also uses Redis.

## Where to work

- Kotlin source: `src/main/kotlin/team/startup/expo`. Feature packages are `domain/{expo,standard,training,image}`; shared security and error handling live in `global`.
- Within a feature, use `presentation` for HTTP controllers/DTOs, `service` for operation interfaces, `service/impl` for implementations, `repository` for JPA, and `entity` for persisted models. Image storage also uses `storage` and `storage/impl`.
- **One API, one service**: each endpoint gets its own service interface and exactly one `*ServiceImpl`. Never group several endpoints into one service (no `add/addAll/update`- or `execute/executeList`-style multi-API services); name them like `CreateTrainingProgramService`, `CreateTrainingProgramListService`, `UpdateTrainingProgramService`. The interface declares `execute(...)`, which the implementation overrides. Do not share one service between endpoints either (for example, `GET /internal/expo/{expo_id}` uses `GetExpoPeriodService`, not `GetExpoDetailService`). Services with no endpoint of their own, such as `AttachExpoImageService` (called by expo create/update) and the scheduled `ImageCleanupService`, keep one interface and one `execute`. This rule does not apply to controllers, repositories, or outgoing `*Client` classes.
- Use constructor injection. Put needed transaction boundaries on service implementations: read-only for database reads and regular transactions for writes. `DeleteExpoServiceImpl` uses `TransactionTemplate` for its retryable local/remote deletion sequence.
- Do not make remote HTTP calls while holding the Expo row lock or the form guard (#160). Program create services run in `ProgramPolicySupport.creating`, and `stage` validates forms outside the lock and only compares form change counts inside it. `findLockedById` waits at most 5 seconds and returns 409.
- On request DTO properties, use explicit `@field:` targets for Jakarta validation and `@field:Valid` for nested DTOs. Validate request DTOs in controllers with `@Valid`; keep path IDs as scalar parameters where the existing API does.

## Contracts to preserve

- Follow the existing `/expo`, `/standard`, `/training`, `/image`, and `/internal/expo` controllers and DTOs. `GET /expo` returns an array without pagination parameters and a page object with `page` or `size`; `PATCH /expo/{expo_id}` replaces required fields and removes omitted programs. Keep existing response statuses and shapes; errors use `ExpectedException(HttpStatus, message)` and `{status, message}`.
- `SecurityConfig` validates RS256 bearer JWTs with `JWT_PUBLIC_KEY`; admin routes require `ROLE_ADMIN`. The token needs numeric `sub`, string `role`, valid `iat`/`exp`, and an issued lifetime no longer than 15 minutes. Coordinate public-key configuration and lifetime with the issuing User service before deployment.
- `/internal/**` checks `X-Internal-Token` against `EXPO_INTERNAL_TOKEN`. Outgoing calls send only the receiver's own token (`USER_INTERNAL_TOKEN`, `APPLICATION_INTERNAL_TOKEN`, `FORM_INTERNAL_TOKEN`, `ATTENTION_INTERNAL_TOKEN`) with no shared fallback; a blank token rejects only that integration's calls with 503 and does not block startup. For integration changes, inspect `StandardDependenciesClient`, `TrainingDependenciesClient`, `ProgramAttendanceClient`, `ExpoDeletionClient`, and `GetExpoValidationServiceImpl` for current paths, DTOs, status codes, and configuration. Expo deletion calls Application, Form, then User before removing local data; preserve retry behavior. A merged PR alone does not establish that dependent services are deployed.

## Logging

The Expo log standard, shared by every Expo service (Spring and Nest alike) so the shared Loki in Expo-Monitoring can label them the same way:

- Every line written to stdout is one JSON object (`logback-spring.xml`, `LogstashEncoder`) with at least `@timestamp` (ISO-8601/RFC3339), `level` (uppercase `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`), `service` (the Eureka app name, `spring.application.name`), `module` and `message`. Other keys (MDC values, `logger_name`, `stack_trace`) may follow. Nothing else goes to stdout: the Spring banner is off and Logback status warnings must not appear.
- The log pipeline reads only these key names and has no per-service fallback. When a service's logs do not show up or the fields do not match, fix the service's log output, never add parsing fallbacks to the pipeline.
- `module` is derived from the logger name by `ModuleNameConverter`, never set at the call site: for `team.startup.expo.{domain|global}.<x>...` it is `<x>`, otherwise the logger name's parent package. Nest services reuse the per-class logger context (`nestjs-pino` `renameContext: 'module'`).
- `HttpAccessLogFilter` writes one access log line per request after the chain completes, before Spring Security so 401/403 are included: `<METHOD> <path> status: <code> (<ms>ms)` with module `HttpAccess` (logger name `HttpAccess`). The path is the matched route template, else the raw URI without query string (max 200 chars); query strings, headers, cookies and bodies are never logged. Level is INFO below 400, WARN for 4xx, ERROR for 5xx and for a client abort (logged as 499). `/actuator/**` and Swagger/API-docs paths are skipped. Disable with `LOGGING_HTTP_ACCESS_ENABLED=false` (`logging.http-access.enabled`, default true).
- With the `local` profile the same JSON is also written to `${EXPO_LOG_DIR:-~/.expo/logs}/<service>/app.log` for the local Loki file tail. Production writes stdout only (pm2 captures it); add no log file settings there.

## Run and check

- Copy `.env.example` to `.env`, then start PostgreSQL 17 and Redis 8 with `docker compose --env-file .env up -d`. Export the `.env` variables before `./gradlew bootRun`; startup needs `DB_PASSWORD` and a valid `JWT_PUBLIC_KEY`.
- Run a focused test with `./gradlew test --tests 'fully.qualified.TestClass'`, or all tests with `./gradlew test`. HTTP and persistence tests use Testcontainers PostgreSQL and need Docker.
- When changing an endpoint, auth rule, or persistence behavior, update the relevant existing HTTP/contract or persistence test to cover the changed behavior.
- Before a code PR, run the CI command `./gradlew build --no-daemon`. Follow `.editorconfig` and the configured ktlint/Spotless checks. For documentation changes, verify referenced paths/commands and run `git diff --check`.
- Put schema changes in versioned `src/main/resources/db/migration` scripts; JPA uses `ddl-auto: validate`.

## Git and PRs

- Check the worktree and current GitHub base/head before work or PR creation. Identify actual predecessor PRs and service-contract dependencies; record dependency PRs and intended merge order in the PR body. PR numbers do not define dependency order.
- After a predecessor merges, bring the latest target branch into each remaining branch, resolve conflicts, and recheck auth, API compatibility, and CI before merge. Treat merge order and deployment prerequisites separately; this document cannot enforce GitHub merge order.
- Preserve other workers' changes. Obtain user approval before committing, pushing, creating a PR, or merging. For requested commits, use the existing `type(scope): Korean description` style.

Task-specific workflows live in `.agents/skills/` and `.claude/skills/`. Use the relevant skill and verify its examples against current source.
