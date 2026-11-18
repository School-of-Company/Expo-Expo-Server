# Claude instructions

Read [AGENTS.md](AGENTS.md) first. It holds the shared test and report rules and points to `.claude/rules/*.md`, which hold this repository's architecture, commands, contracts, database, logging, and Git workflow rules.

For task-specific guidance, use the matching skill in `.claude/skills/`. Claude hooks and permissions are configured in `.claude/settings.json` and `.claude/hooks/`. Treat skill examples as examples; check the current Kotlin source when an example differs from it.

Service rule (see `.claude/rules/architecture.md`): one API = one service interface + one `*ServiceImpl` with `execute(...)`. Do not add a second endpoint's method to an existing service.
