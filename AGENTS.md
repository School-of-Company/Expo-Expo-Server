# Expo Expo Server

Single-module Expo service built with Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, and Gradle. PostgreSQL with Flyway stores application data; local development also uses Redis.

## Where to work

- Kotlin source: `src/main/kotlin/team/startup/expo`. Feature packages are `domain/{expo,standard,training,image}`; shared security and error handling live in `global`.
- Within a feature, use `presentation` for HTTP controllers/DTOs, `service` for operation interfaces, `service/impl` for implementations, `repository` for JPA, and `entity` for persisted models. Image storage also uses `storage` and `storage/impl`.
- A single-purpose **service interface** usually declares `execute(...)`, which its `*ServiceImpl` overrides. Follow existing exceptions such as `TrainingProgramWriteService.add/addAll/update` and `GetExpoListService.executePage`; this naming rule does not apply to controllers or repositories.
- Use constructor injection. Put needed transaction boundaries on service implementations: read-only for database reads and regular transactions for writes. `DeleteExpoServiceImpl` uses `TransactionTemplate` for its retryable local/remote deletion sequence.
- On request DTO properties, use explicit `@field:` targets for Jakarta validation and `@field:Valid` for nested DTOs. Validate request DTOs in controllers with `@Valid`; keep path IDs as scalar parameters where the existing API does.

## Contracts to preserve

- Follow the existing `/expo`, `/standard`, `/training`, `/image`, and `/internal/expo` controllers and DTOs. `GET /expo` returns an array without pagination parameters and a page object with `page` or `size`; `PATCH /expo/{expo_id}` replaces required fields and removes omitted programs. Keep existing response statuses and shapes; errors use `ExpectedException(HttpStatus, message)` and `{status, message}`.
- `SecurityConfig` validates RS256 bearer JWTs with `JWT_PUBLIC_KEY`; admin routes require `ROLE_ADMIN`. The token needs numeric `sub`, string `role`, valid `iat`/`exp`, and an issued lifetime no longer than 15 minutes. Coordinate public-key configuration and lifetime with the issuing User service before deployment.
- `/internal/**` checks `X-Internal-Token` against `EXPO_INTERNAL_TOKEN`. Outgoing service calls use separately configured internal tokens. For integration changes, inspect `StandardDependenciesClient`, `TrainingDependenciesClient`, `ExpoDeletionClient`, and `GetExpoValidationServiceImpl` for current paths, DTOs, status codes, and configuration. Expo deletion calls Application, Form, then User before removing local data; preserve retry behavior. A merged PR alone does not establish that dependent services are deployed.

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
