---
description: 'Use when creating or modifying REST controllers, services, or repositories in hc-professional-ms. Covers JHipster CRUD flow, id validation, partial update mapping, and repository boundaries.'
applyTo: 'src/main/java/net/jojoaddison/web/rest/**/*.java, src/main/java/net/jojoaddison/service/**/*.java, src/main/java/net/jojoaddison/repository/**/*.java'
---

# REST, Service, Repository Patterns

This microservice uses Spring MVC + MongoDB (non-reactive). Keep patterns aligned with existing JHipster-generated resources.

## Layer Responsibilities

- `web/rest`: request validation and HTTP semantics, plus response status/header construction.
- `service`: persistence orchestration and partial-update merge logic.
- `repository`: Spring Data MongoDB interfaces only.

Respect architecture boundaries enforced by ArchUnit tests.

## Resource Class Template

For entity resources in `web/rest`:

- `@RestController`
- `@RequestMapping("/api/<entities>")`
- constructor injection of service + repository
- class constant for entity name
- app name injection via `@Value("${jhipster.clientApp.name}")`

## Standard CRUD Endpoint Contracts

- POST:
  - reject body with existing id -> `idexists`
  - return 201 + creation alert header
- PUT /{id}:
  - reject null body id -> `idnull`
  - reject path/body mismatch -> `idinvalid`
  - reject missing row -> `idnotfound`
  - return 200 + update alert header
- PATCH /{id} (consumes `application/json` and `application/merge-patch+json`):
  - same id guards as PUT
  - use service partial update and `ResponseUtil.wrapOrNotFound(...)`
- GET all: return `List<Entity>`
- GET by id: `ResponseUtil.wrapOrNotFound(...)`
- DELETE: return 204 + deletion alert header

## Service Implementation Pattern

Service classes are concrete `@Service` classes, typically with methods:

- `save` -> repository save
- `update` -> repository save
- `partialUpdate` -> findById, merge non-null fields, save
- `findAll` -> repository findAll
- `findOne` -> repository findById
- `delete` -> repository deleteById

Use SLF4J debug logging at method entry.

## Partial Update Merge Rule

In partial update, only copy non-null incoming fields; never overwrite existing values with nulls.

## Repository Pattern

Mongo repositories should remain minimal:

- `@Repository`
- `interface XRepository extends MongoRepository<X, String>`

Add custom query methods only when a concrete service requirement exists.

## Do Not

- Do not introduce reactive `Mono`/`Flux` types in this service.
- Do not put business merge/query logic in controllers.
- Do not bypass JHipster alert/exception patterns.
