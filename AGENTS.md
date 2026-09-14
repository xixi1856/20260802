# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/main/java/com/blindway`. Modules such as `identity`, `device`, `trip`, and `accessibility` use `api`, `application`, `domain`, and `infrastructure` layers where applicable. Keep cross-cutting technical code in `common` only. Tests mirror production packages under `src/test/java`. Flyway migrations belong in `src/main/resources/db/migration`. Contracts live in `contracts/`, operational configuration in `ops/`, load tests in `load/`, and architecture records in `docs/adr/`.

## Build, Test, and Development Commands

- `docker compose up -d postgres redis minio emqx` starts local dependencies.
- `mvn spring-boot:run -Dspring-boot.run.profiles=local` runs the API locally.
- `mvn test` runs the unit test suite.
- `mvn verify` runs all tests, architecture/style checks, and JaCoCo reporting.
- `mvn spotless:apply` formats Java and supported root-level text files.
- `npm run docs:build` generates contract documentation; `npm run docs:serve` builds and previews it at `127.0.0.1:4173`.

Use JDK 21+, Maven 3.9+, Node 22, and Docker Compose.

## Coding Style & Naming Conventions

Spotless with Palantir Java Format is authoritative; Checkstyle rejects tabs, star imports, missing braces, empty catches, and lines over 140 characters. Use constructor injection and record DTOs; avoid field injection and Lombok `@Data`. Classes use `PascalCase`, methods and fields use `camelCase`, database identifiers use `snake_case`, and JSON uses `camelCase`. Controllers call application services; SQL and MyBatis mappers stay in infrastructure packages.

## Testing Guidelines

Use JUnit 5, ArchUnit, and Testcontainers. Name unit tests `*Test.java` and integration tests `*IT.java`. Add success, validation, authorization, and failure cases near the changed module. Contract request types require matching `.valid.json` and `.invalid.json` examples. Coverage targets are 80% for core logic and 70% overall; inspect `target/site/jacoco`.

## Commit & Pull Request Guidelines

Follow Conventional Commits seen in history, such as `feat: ...`, `perf(accessibility): ...`, and `docs(performance): ...`. Branch from `main` using `feature/*`, `fix/*`, `docs/*`, or `refactor/*`; do not commit directly to `main`. Keep one issue per PR and use squash merge. PRs must link the issue, describe affected modules and behavior, include test evidence, and address contract, migration, security/privacy, observability, and documentation impacts.

## Security & Contracts

Never commit real credentials or precise user tracks; use `.env.example`. Treat `contracts/openapi.yaml`, `contracts/asyncapi.yaml`, and MQTT schemas as authoritative. Update contracts, migrations, tests, implementation, and documentation together, and never edit an already-published Flyway migration.
