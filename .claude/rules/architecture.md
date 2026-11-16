---
paths:
  - "src/main/kotlin/team/startup/expo/**"
---

# Architecture

- Kotlin source: `src/main/kotlin/team/startup/expo`. Feature packages are `domain/{expo,standard,training,image}`; shared security and error handling live in `global`.
- Within a feature, use `presentation` for HTTP controllers/DTOs, `service` for operation interfaces, `service/impl` for implementations, `repository` for JPA, and `entity` for persisted models. Image storage also uses `storage` and `storage/impl`.
- **One API, one service**: each endpoint gets its own service interface and exactly one `*ServiceImpl`. Never group several endpoints into one service (no `add/addAll/update`- or `execute/executeList`-style multi-API services); name them like `CreateTrainingProgramService`, `CreateTrainingProgramListService`, `UpdateTrainingProgramService`. The interface declares `execute(...)`, which the implementation overrides. Do not share one service between endpoints either (for example, `GET /internal/expo/{expo_id}` uses `GetExpoPeriodService`, not `GetExpoDetailService`). Services with no endpoint of their own, such as `AttachExpoImageService` (called by expo create/update) and the scheduled `ImageCleanupService`, keep one interface and one `execute`. This rule does not apply to controllers, repositories, or outgoing `*Client` classes.
- Any write that creates, updates or deletes an expo, standard/training program, preregister session or pending program change (`tb_program_change`, which makes program reads answer 409) must call `ExpoEventSupport.changed(expoId)` inside the same transaction so the `expo.expo.changed` outbox gets a snapshot of the state being committed (`docs/expo-change-events.md`, flag `KAFKA_EXPO_EVENTS_ENABLED`).
- Do not make remote HTTP calls while holding the Expo row lock or the form guard (#160). Program create services run in `ProgramPolicySupport.creating`, and `stage` validates forms outside the lock and only compares form change counts inside it. `findLockedById` waits at most 5 seconds and returns 409.
