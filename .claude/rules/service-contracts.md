---
paths:
  - "src/main/kotlin/team/startup/expo/**/presentation/**"
  - "src/main/kotlin/team/startup/expo/global/client/**"
  - "src/main/kotlin/team/startup/expo/global/security/**"
  - "docs/**"
---

# Service contracts

- Follow the existing `/expo`, `/standard`, `/training`, `/image`, and `/internal/expo` controllers and DTOs. `GET /expo` returns an array without pagination parameters and a page object with `page` or `size`; `PATCH /expo/{expo_id}` replaces required fields and removes omitted programs. Keep existing response statuses and shapes; errors use `ExpectedException(HttpStatus, message)` and `{status, message}`.
- `SecurityConfig` validates RS256 bearer JWTs with `JWT_PUBLIC_KEY`; admin routes require `ROLE_ADMIN`. The token needs numeric `sub`, string `role`, valid `iat`/`exp`, and an issued lifetime no longer than 15 minutes. Coordinate public-key configuration and lifetime with the issuing User service before deployment.
- `/internal/**` checks `X-Internal-Token` against `EXPO_INTERNAL_TOKEN`. Outgoing calls send only the receiver's own token (`USER_INTERNAL_TOKEN`, `APPLICATION_INTERNAL_TOKEN`, `FORM_INTERNAL_TOKEN`, `ATTENTION_INTERNAL_TOKEN`) with no shared fallback; a blank token rejects only that integration's calls with 503 and does not block startup. For integration changes, inspect `StandardDependenciesClient`, `TrainingDependenciesClient`, `ProgramAttendanceClient`, `ExpoDeletionClient`, and `GetExpoValidationServiceImpl` for current paths, DTOs, status codes, and configuration. Expo deletion calls Application, Form, then User before removing local data; preserve retry behavior. A merged PR alone does not establish that dependent services are deployed.
