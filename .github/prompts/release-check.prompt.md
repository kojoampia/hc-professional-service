---
description: 'Run and summarize pre-release checks for hc-professional-ms: required dependencies, prod build, tests, and sonar readiness.'
argument-hint: 'Branch or release tag to validate, plus optional skip list'
agent: 'agent'
tools: [read, search, execute]
---

# Pre-Release Check

Run a consistent pre-release validation workflow for this microservice and produce a pass/fail report.

## Inputs

- `TARGET_REF`: branch/tag/commit label being validated
- `SKIP_STEPS` (optional): comma-separated list from `services,tests,prod-build,sonar`
- `RUN_SONAR` (optional): `true` or `false`

## Required Services

Verify dependencies before build/test runs:

1. Consul at `http://localhost:8500`
2. MongoDB
3. Kafka

Use project scripts when available:

- `npm run docker:consul:up`
- `npm run docker:db:up`
- `npm run docker:kafka:up`
- `npm run services:up`

If dependencies are missing, report exact missing services and stop unless skipped.

## Release Validation Steps

Run in project root using Maven wrapper.

1. Source health snapshot
   - `git status --short`
   - `git rev-parse --short HEAD`
2. Unit/integration baseline
   - `./mvnw verify`
3. Production build
   - `./mvnw -Pprod clean verify`
4. Optional WAR profile check (if release needs WAR)
   - `./mvnw -Pprod,war clean verify`
5. Sonar readiness command (or execution when `RUN_SONAR=true`)
   - `./mvnw -Pprod clean verify sonar:sonar -Dsonar.login=admin -Dsonar.password=admin`

## Output Format

### 1) Release Context

- target ref
- current commit
- dirty/clean working tree

### 2) Service Prerequisite Check

- Consul: pass/fail
- MongoDB: pass/fail
- Kafka: pass/fail
- remediation commands used or recommended

### 3) Command Results

For each executed step:

- command
- status: pass/fail/skipped
- duration (if available)
- key failure excerpt (actionable lines only)

### 4) Final Gate Decision

- READY or NOT READY
- blocking issues (numbered)
- non-blocking warnings

### 5) Next Commands

Only the actionable commands required to reach READY.

## Rules

- Prefer existing project scripts where they map directly to the required checks.
- Do not claim pass unless command was executed in-session.
- If a step is skipped, state why.
- Keep output concise and release-focused.
