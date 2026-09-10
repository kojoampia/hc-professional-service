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

- `domain/` — MongoDB documents. Generated CRUD entities: `Activity`, `Address`, `Category`, `PersonalDocument`, `Metadata`, `Profile`, `Report`, `Task`, `Team`, `DutyRoster`. Onboarding entities: `ProfessionalApplication`, `OnboardingEvent`, and `EmergencyContact` (embedded in `Profile`). All extend `AbstractAuditingEntity`.
- `domain/enumeration/` — `DocumentType`, `VerificationStatus` (document credentialing verdict), `OnboardingStatus` (application lifecycle), `DutyRole` and `ShiftType` (duty roster). Each carries a Javadoc stating its contract — read it before adding a value; `ShiftType`'s time windows and `DutyRole`'s eight-discipline alignment are mirrored in `web/`. `DutyRole.ANGEL` was retired on 2026-09-08 (`../docs/backlog.md` item 44) and `AngelDutyRoleMigration` in `config/` deletes the rows that carried it.
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
  - **`Profile`, `Category` and `Team` have no `DELETE`, and regeneration puts all three back.** They were removed because each was a bare `deleteById` orphaning the rows that point at the deleted one — six collections for a profile (`../docs/backlog.md` item 56), `Profile.specialtyCategoryId` for a category and `Profile.teamIds` plus `Task.teamId` for a team (item 57) — and none of it was announced. The `.jhipster` definitions still describe full CRUD and **cannot be made to say otherwise**: `readOnly: true` suppresses POST/PUT/PATCH too and there is no per-verb knob. So the guard is entirely in the tests — `thereIsNoDeleteOnThisResource` in each of the three `*ResourceIT`s, the `NO_DELETE` branch of `LocationHeaderIT.removeCreatedRow`, and `ReferenceDataDeletionIT`, which asserts it from the pointer rather than from the path.

## Onboarding: the part that isn't generated code

`../docs/professional-onboarding-workflow.md` (the workspace root, since it spans all three repos) is the authoritative spec — read it before touching applications, documents, authorities, or the roster. Java comments throughout this repo cite it by bare filename.

Two further cross-repo documents sit beside it and are the origin of contracts this service owes: `../docs/professional-dashboard-migration-plan.md` (the dashboard, patient, med-case and duty-roster endpoint contracts) and `../docs/phase_4_contract_reconciliation.md` (which frontend models still have no backend contract, classified Existing / Missing / Awaiting confirmation).

- **`OnboardingService` holds a server-side state machine.** The `LEGAL_TRANSITIONS` map over `OnboardingStatus` decides what may follow what; clients never do. Every accepted transition appends an `OnboardingEvent`, and illegal ones are rejected server-side (proven by `OnboardingFlowIT`). Three document gates sit on top of transition legality, each on a different operation — don't conflate them: `submitForReview` requires the mandatory documents to be **present**, an `APPROVED` decision requires them **verified**, and reactivating from `SUSPENDED` to `ACTIVE` requires a **current, verified licence**.
- **Applicants hold only `ROLE_USER`**, so the mutation matrix below blocks them from the normal entity endpoints. `/api/onboarding/**` is therefore `.authenticated()` rather than role-gated, and an applicant's profile is written through `OnboardingService.upsertOwnProfile`, which **force-sets `accountId` to the caller** — keep that invariant in any new onboarding write path.
- **`accountId` is the gateway's `User.id`, read from the `uid` claim** (`../docs/backlog.md` item 50, 2026-09-10). It was the JWT **subject** — the login — from WP1 until then, which is why so much of this repo's history is about one field: the gateway keys its own account events on `User.id`, so the two producers on `hc.professional.registration` named one clinician differently and hc-admin's join matched nothing. `SecurityUtils.getCurrentAccountId()` is who is calling; `getCurrentUserLogin()` is for `createdBy`, `lastModifiedBy` and every event `actor`, which name a person for a person to read and are never looked up. **The line is: anything compared against stored data uses the account id, anything written down for a person uses the login.**

  Three consequences worth carrying. **There is no fallback** — a token carrying no `uid`, or one minted by hc-admin or hc-patient (whose `uid` names a row in their user store, discarded by `SecurityUtils.MINTING_ISSUER`), resolves to nobody and is refused; adding a fallback to the subject reintroduces the second join key the item removed. **`Profile.accountUid` is gone**, folded into `accountId` now that they hold the same value. And **the stored data was moved by `AccountIdMigrationService`**, an admin endpoint (`POST /api/admin/account-id-migration`, `dryRun` by default) rather than an `ApplicationRunner` like `ShiftTypeMigration`, because the login→id mapping lives in the gateway's user store and a startup runner has no caller whose token it could relay. A row whose login resolved to nothing has its key cleared and the value recorded in `OrphanedAccountRow` — not synthesised, not left holding a login.

  **In tests, use `@WithMockGatewayUser` rather than `@WithMockUser`**: the latter installs a string principal with no claims, so every own-scoped endpoint 401s under it. Its account id defaults to `"uid-" + login`, deliberately unlike the login, so a regression to resolving by `sub` fails the suite rather than passing on a coincidence.

- Attribution (`source`, from the careers handoff) is normalized and length-capped so the field can't be used as free storage. See `../docs/careers-handoff-contract.md`.
- `ComplianceScheduler` runs the expiry sweep nightly at 04:00 (`@Scheduled`; `@EnableScheduling` lives in `AsyncConfiguration`) over `PersonalDocument.expiryDate`, emitting `compliance.alert`. The same sweep is also an on-demand admin endpoint in `ComplianceResource` — **both paths must stay idempotent per day**, since either can run first.

## Security: the mutation matrix

`config/SecurityConfiguration` gates by **route and HTTP method**, not by annotations:

- `GET /api/**` — any authenticated role.
- `POST`/`PUT`/`PATCH`/`DELETE` `/api/**` — `AuthoritiesConstants.CLINICAL_MUTATION` only: admin, doctor, nurse, paramedic, pharmacist, therapist. **Carer, chemist and technician are read-only in v1.**
- `/api/admin/**` — admin. `/api/onboarding/**` — any authenticated user (see above).

`ClinicalAuthorityMatrixIT` proves the split per role; if you change the matrix, change that test with it. The eight clinical disciplines are declared in three repos (here, `gateway/security/AuthoritiesConstants`, and web's `authority.constants.ts`/`authority-role.ts`) and drift silently — web expresses the same six mutating roles differently, admin/doctor via an early return in `hasHealthConnectPermission`.

**`ROLE_ANGEL` is not an authority of this subsystem** (`../docs/backlog.md` item 44, 2026-09-08). A care angel supports one named patient; hc-patient holds the authority — an `ACTIVE CareDelegation` re-read per request — and the whole surface for it. Nothing here names it. **A token carrying it still arrives**, because the three gateways share one signing key and this service validates no issuer, and because an account on a long-lived database may hold a grant made before the removal. Such a caller is exactly a role-less applicant: everything gated on `CLINICAL_AND_ADMIN` or `CLINICAL_MUTATION` refuses it (both are positive lists), and everything `.authenticated()` still serves it. `AuthoritiesConstantsUnitTest` fails if the literal reappears in any privilege set in that class; `ClinicalAuthorityMatrixIT` holds the runtime behaviour with real requests.

This service only **validates** JWTs; it issues none.

## Domain events

`DomainEventPublisher` publishes to `hc.professional.entity` via `StreamBridge`: `entity.created` on create paths, `compliance.alert` from the WP7 sweep. Envelope is `eventId`/`eventType`/`occurredAt`/`source`/`actor`/`payload`, keyed by entity id so per-entity ordering holds; delivery is at-least-once and consumers dedupe on `eventId`.

Two rules: **publishing must never break the write path** (failures are logged, not propagated — keep the try/catch), and payloads carry **identifiers only, no PII**. The gateway publishes `registration.created` to a separate topic. `DomainEventsKafkaIT` asserts both the envelope and the topic.

**`ProfileStatus` is the exception to "the handler publishes", and it must stay one.** It rides `hc.professional.registration` in the `ProfessionalEvent` envelope and is announced by `service/ProfileStatusAnnouncer`, a Mongo `AbstractMongoEventListener` that fires on any save of `Profile`, `PersonalDocument` or `ProfessionalApplication` — the three documents whose state the frame reports. **Do not add a `publishProfileStatus` call to a new resource or service.** It was called from a table of four handlers until 2026-09-08, and `../docs/backlog.md` item 49 is what the table missed: a clinician renewing their own licence took `isVerified` to false and told nobody, because a list of call sites cannot fail when a fifth is written. `ProfileStatusAnnouncementFilter` (`web/filter/`) makes the flush one frame per request; `ProfileStatusOnEveryWriteIT` proves a bare repository save announces, which is the property the listener exists for. The announcer's javadoc lists what it does not cover — query-based updates, profile deletion, `entity.created`.

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

**There is no image build in this repo.** `build-image.sh` was deleted on 2026-09-06 (`../docs/backlog.md` item 34); it drove Jib against `docker-registry.jojoaddison.net`, a registry hostname that is not in use, and no image had been built from it since the deployment bundle was restructured in August. The production image is built from `../deploy/docker/api.Dockerfile` with this repo as the build context — by `../deploy/build.sh` on the `local` channel, and by this repo's own `.github/workflows/release.yml` on the `github` channel, which checks out `hc-professional-ci` for the Dockerfile.

### Build toolchain gotchas

The pom targets **release 25** and builds on **JDK 25**. The enforcer's `requireJavaVersion` is `[25,27)` as of 2026-08-08; it used to be `[26,)`, which demanded a JDK newer than the bytecode this build emits and made a plain `./mvnw verify` fail on a JDK 25 host with `release version 25 not supported`. 26 stays in range on purpose — `../deploy/docker/api.Dockerfile` builds on `maven:3.9-eclipse-temurin-26` — so don't narrow it without changing that Dockerfile too.

**Build with `JAVA_HOME=/usr/lib/jvm/jdk-25.0.2-oracle-x64`, and verify with `clean verify`.** The workstation's ambient `JAVA_HOME` is `/usr/lib/jvm/java-25-openjdk-amd64`, a **JRE with no `javac`**. This repo happens to survive it — its compiler plugin forks to the `javac` on `PATH`, which is the real 25.0.2 — while `gateway`'s uses the in-process compiler and fails with `release version 25 not supported`, a message that points at the enforcer range rather than at the missing compiler. An incremental build hides the problem entirely, because a populated `target/classes` needs no compiler at all. Two related pins exist because Jib 3.4.1's bundled ASM cannot read Java 25 class files (major 69): `jib-maven-plugin.version` is **3.4.6**, and the jib `<container>` block sets an explicit `<mainClass>` so Jib never falls back to its class scan. Both carry explanatory comments — don't "clean up" either.

Deployment of the whole three-repo stack lives in `../deploy/` at the workspace root (runbook in its `README.md`), not here — it is its own git repository, `hc-professional-ci`, and it supplies the Dockerfile rather than calling anything in this repo.

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
