# AGENTS.md — professionalService

Guidance for AI agents working in this repository. Describes the code as it actually is.

## What this repository is

A JHipster 8.2.1–generated **imperative Spring MVC microservice** holding the professional-domain entities of the Health Connect platform. It sits behind the `hcProfessionalGateway` (sibling repo `gateway/`) and is reached through the gateway as `/services/<service-id>/api/...`. It has no UI and no user store of its own.

## Actual technology stack

- Java 25, Spring Boot 4.1 (`spring-boot-starter-parent` 4.1.0), Maven (`./mvnw`)
- **Spring MVC (`spring-boot-starter-web`) — imperative/blocking.** Do not copy `Mono`/`Flux` patterns from the reactive gateway repo into this one.
- **MongoDB** via Spring Data MongoDB (`MongoRepository` interfaces). There is **no PostgreSQL, no JPA, no Liquibase, no Mongock, no MinIO** — ignore any doc that claims otherwise. No migration framework: collections/documents are created as written.
- Consul for service discovery and config. **The app refuses to start if Consul is not reachable at `http://localhost:8500`.**
- JWT auth: this service only **validates** tokens issued by the gateway (`security/`, `SecurityJwtConfiguration`); there are no login/user endpoints here.
- Kafka via Spring Cloud Stream binder (`broker/KafkaConsumer`, `broker/KafkaProducer`).
- springdoc-openapi (WebMVC variant) for API docs.
- **No Lombok** — explicit getters/setters and fluent JHipster-style builders on domain classes.

Server port: **8081** (dev).

## Code layout (`src/main/java/net/jojoaddison`)

- `domain/` — MongoDB documents: `Activity`, `Address`, `Category`, `PersonalDocument`, `Metadata`, `Profile`, `Report`, `Roster`, `Task`, `Team`; `enumeration/DocumentType`. All extend `AbstractAuditingEntity`.
- `repository/` — one `MongoRepository` per entity.
- `web/rest/` — one CRUD `*Resource` per entity plus `professionalServiceKafkaResource` (note the lowercase-p class name — existing quirk).
- `service/` — **deliberately thin**: only `IDocumentService` and `ProfileService` exist. Most resources call repositories directly; follow whichever pattern the entity you're touching already uses, and don't introduce a DTO/mapper layer that isn't there.
- `.jhipster/*.json` — JHipster entity definitions. **`DutyRoster.json` and `Patient.json` exist but have no generated domain/repository/resource classes yet** — they are planned entities, not dead references.

## Commands

```bash
npm run services:up        # start Consul + MongoDB + Kafka (docker compose -f src/main/docker/services.yml up --wait)
npm run docker:db:up       # MongoDB only
./mvnw                     # run dev profile (needs Consul + MongoDB)
./run-local.sh <args>      # wrapper: exports SPRING_MONGODB_URI from .env.local (copy .env.local.example), then runs ./mvnw
./mvnw verify              # full build + unit + integration tests
./mvnw test -Dtest=SomeTest              # single unit test
./mvnw verify -Dit.test=ProfileResourceIT    # single integration test
./mvnw -Pprod clean verify # production jar → java -jar target/*.jar
./mvnw checkstyle:check    # style gate (checkstyle.xml, includes nohttp)
npm run lint / lint:fix    # ESLint (tooling/config files)
npm run prettier:check / prettier:format
```

## Testing

- JUnit 5. `*ResourceIT` tests use `@IntegrationTest`, which wires a **Testcontainers MongoDB** (`config/MongoDbTestContainer`, `TestContainersSpringContextCustomizerFactory`) — Docker must be running for `./mvnw verify`.
- MockMvc for endpoint tests (imperative stack).
- `TechnicalStructureTest` enforces layering rules with ArchUnit — if it fails after your change, fix the dependency direction rather than editing the rule.
- JaCoCo coverage and Sonar (`sonar-project.properties`) are wired into the build; Spotless is configured in the pom.

## Conventions

- Preserve JHipster generator needles (`// jhipster-needle-*`).
- Prettier formats Java too — run `npm run prettier:format` after editing.
- REST errors follow the JHipster problem-details setup in `web/rest/errors/` (RFC 7807 style) — throw `BadRequestAlertException` and friends rather than ad-hoc responses.
- Configuration lives in `src/main/resources/config/application*.yml`; Consul central config in `src/main/docker/central-server-config/`.
