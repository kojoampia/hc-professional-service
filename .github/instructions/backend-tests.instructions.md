---
description: 'Use when writing, reviewing, or updating Java tests in hc-professional-ms. Covers IntegrationTest setup, MockMvc CRUD assertions, repository-count helpers, and architecture/security test boundaries.'
applyTo: 'src/test/**/*.java'
---

# Java Test Patterns

Use these rules for test files in this microservice.

## Test Stack And Base Annotations

- Integration REST tests use:
  - `@IntegrationTest`
  - `@AutoConfigureMockMvc`
  - `@WithMockUser`
- `@IntegrationTest` composes Spring Boot test setup plus embedded dependencies for this service.

## REST Integration Test Class Shape

- Place REST integration tests in `src/test/java/.../web/rest/*ResourceIT.java`.
- Include constants for defaults/updated values:
  - `DEFAULT_*`
  - `UPDATED_*`
- Include endpoint constants:
  - `ENTITY_API_URL`
  - `ENTITY_API_URL_ID = ENTITY_API_URL + "/{id}"`
- Use `ObjectMapper` for request/response serialization.
- In `@BeforeEach`:
  - call `repository.deleteAll()`
  - initialize entity with `createEntity()`.

## CRUD Test Coverage Requirements

For each resource, include all cases below.

1. Create success (POST) -> 201
2. Create with existing ID -> 400
3. Get all (GET) -> 200 + jsonPath assertions
4. Get by id (GET /{id}) -> 200
5. Get non-existing -> 404
6. Put success (PUT /{id}) -> 200
7. Put non-existing id -> 400
8. Put id mismatch -> 400
9. Put missing id path param -> 405
10. Patch partial (application/merge-patch+json) -> 200
11. Patch full -> 200
12. Patch non-existing -> 400
13. Patch id mismatch -> 400
14. Patch missing id path param -> 405
15. Delete (DELETE /{id}) -> 204

## MockMvc Patterns

- Use request builders: `post`, `get`, `put`, `patch`, `delete`.
- For body payloads, serialize with `om.writeValueAsBytes(entity)`.
- For response parsing, deserialize via `om.readValue(...)`.
- Content type for merge patch must be exactly `application/merge-patch+json`.

## Repository Count And Persistence Helpers

Add helper methods in each resource IT class:

- `getRepositoryCount()`
- `assertIncrementedRepositoryCount(long)`
- `assertDecrementedRepositoryCount(long)`
- `assertSameRepositoryCount(long)`
- `getPersistedEntity(Entity)`

Use these helpers in create/update/delete tests instead of inline count arithmetic.

## Assertion Utilities

Prefer domain assertion helpers from `src/test/java/.../domain/*Asserts.java` and use `TestUtil.createUpdateProxyForBean(partial, original)` for merge-patch verification.

## Architecture And Security Tests

- Keep architecture tests in `TechnicalStructureTest` using ArchUnit layered rules.
- Keep security token/auth behavior tests under `security/jwt`.
- Do not mix endpoint CRUD tests with architecture/security assertions in the same class.

## Naming Rules

- Integration tests: `*IT.java` or `*IntTest.java`
- Unit tests: `*Test.java`

Match existing naming patterns to keep Maven test execution predictable.
