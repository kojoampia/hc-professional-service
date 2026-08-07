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

- `domain/` — MongoDB documents. Generated CRUD entities: `Activity`, `Address`, `Category`, `PersonalDocument`, `Metadata`, `Profile`, `Report`, `Roster`, `Task`, `Team`, `DutyRoster`. Onboarding entities: `ProfessionalApplication`, `OnboardingEvent`, and `EmergencyContact` (embedded in `Profile`). All extend `AbstractAuditingEntity`.
- `domain/enumeration/` — `DocumentType`, `VerificationStatus` (document credentialing verdict), `OnboardingStatus` (application lifecycle), `DutyRole` and `ShiftType` (duty roster). Each carries a Javadoc stating its contract — read it before adding a value; `ShiftType`'s time windows and `DutyRole`'s nine-role alignment are mirrored in `web/`.
- `repository/` — one `MongoRepository` per entity.
- `web/rest/` — one CRUD `*Resource` per generated entity, plus the hand-written `OnboardingResource`, `OnboardingDocumentResource`, `DutyRosterResource`, `ComplianceResource`, and `professionalServiceKafkaResource` (note the lowercase-p class name — existing quirk).
- `service/` — thin for the **generated** entities (most `*Resource` classes call repositories directly; don't introduce a DTO/mapper layer that isn't there), but the onboarding domain has real services: `OnboardingService` (state machine, see below), `ComplianceService` + `ComplianceScheduler`, `PersonalDocumentService`, `ProfileService`. Follow whichever pattern the area you're touching already uses.
- `broker/` — `KafkaConsumer`/`KafkaProducer` scaffold plus `DomainEventPublisher` and `DomainEventEnvelope` (see § Domain events).
- `.jhipster/*.json` — JHipster entity definitions, kept in sync with the domain classes as of 2026-07-30. `DutyRoster` was generated in WP6 (with `patientId` dropped and the enum corrected — the JDL's `MEDIC`/`VENDOR`/`ADMINISTRATOR` values are gone). `Patient.json` was removed: it was a mislabelled copy of an outdated `Profile` (internal `name` was `Profile`), and `Patient` belongs to `patientservice`, a backend not in this workspace.

  **Do not regenerate entities from these definitions without reading this first.** They describe the fields accurately, but regeneration still destroys hand-written code the templates know nothing about:

  - Every `*Resource` injects `DomainEventPublisher` for WP3 `entity.created` publishing; the generated template injects only the repository. Regenerating any entity silently drops the event wiring and fails `DomainEventsKafkaIT`.
  - `ProfileRepository` (`findByAccountId`, `findByEmail`), `PersonalDocumentRepository` (`findByProfileId`, `findByTypeAndExpiryDateLessThan` — the compliance sweep) and `DutyRosterRepository` (two ordering finders) carry hand-added methods that generation deletes.
  - Three `Profile` fields and one on `Team` cannot be expressed in this format and are stored as `String` approximations: `Profile.address` (embedded `Address`), `Profile.emergencyContact` (embedded `EmergencyContact`), `Profile.teamIds` and `Team.members` (both `List<String>`). Regenerating those two entities would emit `String` and break their consumers.
  - `dto` and `service` are deliberately `no` on every entity. Setting `dto: mapstruct` or `service: serviceClass` makes the generator create a DTO/mapper/service layer this repo does not use, and overwrite the hand-written `ProfileService`/`PersonalDocumentService`.

## Onboarding: the part that isn't generated code

`../docs/professional-onboarding-workflow.md` (the workspace root, since it spans all three repos) is the authoritative spec — read it before touching applications, documents, authorities, or the roster. Java comments throughout this repo cite it by bare filename.

Two further cross-repo documents sit beside it and are the origin of contracts this service owes: `../docs/professional-dashboard-migration-plan.md` (the dashboard, patient, med-case and duty-roster endpoint contracts) and `../docs/phase_4_contract_reconciliation.md` (which frontend models still have no backend contract, classified Existing / Missing / Awaiting confirmation).

- **`OnboardingService` holds a server-side state machine.** The `LEGAL_TRANSITIONS` map over `OnboardingStatus` decides what may follow what; clients never do. Every accepted transition appends an `OnboardingEvent`, and illegal ones are rejected server-side (proven by `OnboardingFlowIT`). Three document gates sit on top of transition legality, each on a different operation — don't conflate them: `submitForReview` requires the mandatory documents to be **present**, an `APPROVED` decision requires them **verified**, and reactivating from `SUSPENDED` to `ACTIVE` requires a **current, verified licence**.
- **Applicants hold only `ROLE_USER`**, so the mutation matrix below blocks them from the normal entity endpoints. `/api/onboarding/**` is therefore `.authenticated()` rather than role-gated, and an applicant's profile is written through `OnboardingService.upsertOwnProfile`, which **force-sets `accountId` to the caller** — keep that invariant in any new onboarding write path.
- Attribution (`source`, from the careers handoff) is normalized and length-capped so the field can't be used as free storage. See `../docs/careers-handoff-contract.md`.
- `ComplianceScheduler` runs the expiry sweep nightly at 04:00 (`@Scheduled`; `@EnableScheduling` lives in `AsyncConfiguration`) over `PersonalDocument.expiryDate`, emitting `compliance.alert`. The same sweep is also an on-demand admin endpoint in `ComplianceResource` — **both paths must stay idempotent per day**, since either can run first.

## Security: the mutation matrix

`config/SecurityConfiguration` gates by **route and HTTP method**, not by annotations:

- `GET /api/**` — any authenticated role.
- `POST`/`PUT`/`PATCH`/`DELETE` `/api/**` — `AuthoritiesConstants.CLINICAL_MUTATION` only: admin, doctor, nurse, paramedic, pharmacist, therapist. **Carer, angel, chemist and technician are read-only in v1.**
- `/api/admin/**` — admin. `/api/onboarding/**` — any authenticated user (see above).

`ClinicalAuthorityMatrixIT` proves the split per role; if you change the matrix, change that test with it. The nine clinical authorities are declared in three repos (here, `gateway/security/AuthoritiesConstants`, and web's `authority.constants.ts`/`authority-role.ts`) and drift silently — web expresses the same six mutating roles differently, admin/doctor via an early return in `hasHealthConnectPermission`.

This service only **validates** JWTs; it issues none.

## Domain events

`DomainEventPublisher` publishes to `hc.professional.entity` via `StreamBridge`: `entity.created` on create paths, `compliance.alert` from the WP7 sweep. Envelope is `eventId`/`eventType`/`occurredAt`/`source`/`actor`/`payload`, keyed by entity id so per-entity ordering holds; delivery is at-least-once and consumers dedupe on `eventId`.

Two rules: **publishing must never break the write path** (failures are logged, not propagated — keep the try/catch), and payloads carry **identifiers only, no PII**. The gateway publishes `registration.created` to a separate topic. `DomainEventsKafkaIT` asserts both the envelope and the topic.

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
./build-image.sh [version] # WP8: Jib production image hc-professional-service; PUSH=1 to push to the registry
```

### Build toolchain gotchas

The pom targets **release 25** and builds on **JDK 25**. The enforcer's `requireJavaVersion` is `[25,27)` as of 2026-08-08; it used to be `[26,)`, which demanded a JDK newer than the bytecode this build emits and made a plain `./mvnw verify` fail on a JDK 25 host with `release version 25 not supported`. 26 stays in range on purpose — `../deploy/docker/api.Dockerfile` builds on `maven:3.9-eclipse-temurin-26` — so don't narrow it without changing that Dockerfile too. `build-image.sh` still pins `JAVA_HOME=/usr/lib/jvm/jdk-26-oracle-x64` when present; it is superseded by `../deploy/docker/` and the pin is harmless while 26 remains in range. Two related pins exist because Jib 3.4.1's bundled ASM cannot read Java 25 class files (major 69): `jib-maven-plugin.version` is **3.4.6**, and the jib `<container>` block sets an explicit `<mainClass>` so Jib never falls back to its class scan. Both carry explanatory comments — don't "clean up" either.

Deployment of the whole three-repo stack lives in `../deploy/` at the workspace root (`docker-compose.professional.yml`, runbook in its `README.md`), not here. It invokes this repo's `build-image.sh` as `(cd ../api && ./build-image.sh <version>)`.

## Testing

- JUnit 5. `*ResourceIT` tests use `@IntegrationTest`, which wires a **Testcontainers MongoDB** (`config/MongoDbTestContainer`, `TestContainersSpringContextCustomizerFactory`) — Docker must be running for `./mvnw verify`. Kafka assertions use `KafkaTestContainer`.
- MockMvc for endpoint tests (imperative stack). Generated ITs run as `ROLE_DOCTOR` so they satisfy the mutation matrix.
- The hand-written flow ITs are the real specification of the onboarding behaviour and are where new coverage belongs: `OnboardingFlowIT` (legal/illegal transitions), `OnboardingContractsIT`, `ReviewerFlowIT`, `ComplianceFlowIT`, `DutyRosterFlowIT`, `ClinicalAuthorityMatrixIT` (the mutation matrix), `DomainEventsKafkaIT` (event envelopes).
- `spring-boot-security-test` must stay on the test classpath — without it `@WithMockUser` silently becomes a no-op and every secured test 401s.
- `TechnicalStructureTest` enforces layering rules with ArchUnit — if it fails after your change, fix the dependency direction rather than editing the rule.
- JaCoCo coverage and Sonar (`sonar-project.properties`) are wired into the build; Spotless is configured in the pom.

## Conventions

- Preserve JHipster generator needles (`// jhipster-needle-*`).
- Prettier formats Java too — run `npm run prettier:format` after editing.
- REST errors follow the JHipster problem-details setup in `web/rest/errors/` (RFC 7807 style) — throw `BadRequestAlertException` and friends rather than ad-hoc responses.
- Configuration lives in `src/main/resources/config/application*.yml`; Consul central config in `src/main/docker/central-server-config/`.
