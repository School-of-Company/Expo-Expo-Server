---
paths:
  - "**"
---

# Workflow

## Run and check

- Copy `.env.example` to `.env`, then start PostgreSQL 17 and Redis 8 with `docker compose --env-file .env up -d`. Export the `.env` variables before `./gradlew bootRun`; startup needs `DB_PASSWORD` and a valid `JWT_PUBLIC_KEY`.
- Run a focused test with `./gradlew test --tests 'fully.qualified.TestClass'`, or all tests with `./gradlew test`. HTTP and persistence tests use Testcontainers PostgreSQL and need Docker.
- When changing an endpoint, auth rule, or persistence behavior, update the relevant existing HTTP/contract or persistence test to cover the changed behavior.
- Before a code PR, run the CI command `./gradlew build --no-daemon`. Follow `.editorconfig` and the configured ktlint/Spotless checks. For documentation changes, verify referenced paths/commands and run `git diff --check`.

## Git and PRs

- Check the worktree and current GitHub base/head before work or PR creation. Identify actual predecessor PRs and service-contract dependencies; record dependency PRs and intended merge order in the PR body. PR numbers do not define dependency order.
- After a predecessor merges, bring the latest target branch into each remaining branch, resolve conflicts, and recheck auth, API compatibility, and CI before merge. Treat merge order and deployment prerequisites separately; this document cannot enforce GitHub merge order.
- Preserve other workers' changes. Obtain user approval before committing, pushing, creating a PR, or merging. For requested commits, use the existing `type(scope): Korean description` style.

Task-specific workflows live in `.agents/skills/` and `.claude/skills/`. Use the relevant skill and verify its examples against current source.
