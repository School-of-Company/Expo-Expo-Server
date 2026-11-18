---
paths:
  - "**/*.kt"
---

# Kotlin conventions

- Use constructor injection. Put needed transaction boundaries on service implementations: read-only for database reads and regular transactions for writes. `DeleteExpoServiceImpl` uses `TransactionTemplate` for its retryable local/remote deletion sequence.
- On request DTO properties, use explicit `@field:` targets for Jakarta validation and `@field:Valid` for nested DTOs. Validate request DTOs in controllers with `@Valid`; keep path IDs as scalar parameters where the existing API does.
