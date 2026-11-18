---
paths:
  - "src/main/kotlin/team/startup/expo/**/repository/**"
  - "src/main/kotlin/team/startup/expo/**/entity/**"
  - "src/main/resources/db/migration/**"
---

# Database

- Access the database through Spring Data JPA repositories. Use QueryDSL for dynamic filters, sorting and pagination on mapped entities; fixed queries and single-row saves use JPQL, derived queries or repository saves.
- Keep PostgreSQL atomic operations (`FOR UPDATE SKIP LOCKED`, `RETURNING`, `ON CONFLICT`, CTE, `UNION`, jsonb functions and DB-time decisions with `clock_timestamp()`/`INTERVAL`) in repository `@Query(nativeQuery = true)` methods.
- Flush pending JPA writes before native operations, including event snapshots in `beforeCommit`. Bulk mutations must account for stale managed entities; clear only after flushing, and avoid detaching objects that callers still need to mutate. DB-default timestamps are mapped with `insertable = false, updatable = false`.
- Put schema changes in versioned `src/main/resources/db/migration` scripts; JPA uses `ddl-auto: validate`.
