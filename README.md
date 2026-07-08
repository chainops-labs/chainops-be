# chainops-be

ChainOps의 장애 생명주기와 MTTR 계산을 담당하는 Kotlin Spring Boot MVC API입니다.

## 기술 스택

- Kotlin
- Spring Boot MVC
- PostgreSQL schema + Flyway migration
- MTTR metric API
- Docker
- GitHub Actions CI

## API 범위

- 장애 생성
- 장애 상태 변경
- 장애 이벤트 기록
- rollback checklist 상태 관리
- MTTR 요약

## 로그 참조

- 장애 record는 `trace_id`, `elk_url`을 저장합니다.
- 원문 로그 본문은 API database에 저장하지 않습니다.
- seed data에는 해결된 장애와 진행 중인 장애 drill을 포함합니다.
