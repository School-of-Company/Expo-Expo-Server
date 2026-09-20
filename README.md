# Expo Expo Server

Java 21, Spring Boot 4.1.1, Kotlin 2.3.21 기반 서버입니다.

## 검증

Docker를 실행한 상태에서 빌드합니다. 테스트용 PostgreSQL은 Testcontainers가 자동으로 생성하고 종료합니다.

```sh
./gradlew build
./gradlew spotlessApply
```

`build`는 테스트, ktlint, Spotless 검사를 포함합니다.

## 로컬 실행

Docker를 실행한 뒤 프로젝트 루트에서 실행합니다. 최초 한 번 `.env`를 만듭니다.

```sh
cp .env.example .env
docker compose up -d --wait
```

PostgreSQL은 `localhost:15432`, Redis는 `localhost:16379`로 연결합니다.
Compose는 `.env`를 읽지만 `bootRun`은 자동으로 읽지 않으므로 다음과 같이 실행합니다.

```sh
(
  set -a
  . ./.env
  set +a
  ./gradlew bootRun
)
```

IDE에서 실행할 때도 `.env`의 값을 실행 구성의 환경 변수에 넣습니다.
로컬에서는 Eureka 연결을 비활성화합니다.

```sh
docker compose ps
docker compose down
```

PostgreSQL 데이터는 볼륨에 유지됩니다. `docker compose down -v`는 DB 데이터까지 삭제합니다.
Redis는 임시 캐시로 사용하며 컨테이너 재생성 시 데이터 유지를 보장하지 않습니다.
`.env`의 DB 계정 변경은 기존 PostgreSQL 볼륨의 계정을 변경하지 않습니다.

스키마 변경은 Flyway 마이그레이션(`src/main/resources/db/migration`)으로 관리합니다.

API 문서는 `/swagger-ui/index.html`, OpenAPI 명세는 `/v3/api-docs`에 있습니다.
현재 Spring Security 기본 인증이 적용되어 있어 로그인해야 합니다.
