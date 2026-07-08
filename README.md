# chainops-be

Kotlin Spring Boot MVC API for ChainOps incident lifecycle and MTTR operations.

## Stack

- Kotlin
- Spring Boot MVC
- PostgreSQL schema + Flyway migration
- MTTR metric API
- Docker
- GitHub Actions CI

## API Scope

- Incident creation.
- Incident status updates.
- Incident event recording.
- Rollback checklist state.
- MTTR summary.

## Log References

- Incidents store `trace_id` and `elk_url`.
- Raw log bodies stay outside the API database.
- Seed data includes resolved and active incident drills.
