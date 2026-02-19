# Spec: Real-Time Fraud Detection Simulator (MVP)

## Product Vision
- Detect suspicious transactions in near real time with explainable rules.
- Block high-risk operations and emit alerts for review.
- Provide auditability and observability for risk decisions.

## User Scenarios and Testing

### User Story 1 - Score an incoming event (Priority: P1)
As a risk analyst, I want each event scored by clear rules so I can understand the decision.

**Why this priority**: Scoring is the core of fraud detection.

**Independent Test**: Submit a high-risk event and verify a high score with reasons.

**Acceptance Scenarios**:
1. **Given** an event with high amount and unusual country, **When** it is ingested, **Then** a high risk score is computed with rule reasons.
2. **Given** a normal event, **When** it is ingested, **Then** the risk score remains below the block threshold.

---

### User Story 2 - Block suspicious activity (Priority: P2)
As an operations manager, I want high-risk transactions blocked automatically to reduce losses.

**Why this priority**: Blocking reduces fraud impact.

**Independent Test**: Ingest an event above threshold and confirm the decision is BLOCK with an alert record.

**Acceptance Scenarios**:
1. **Given** a score above threshold, **When** the decision is made, **Then** the transaction is marked BLOCK and an alert is stored.

---

### User Story 3 - Review alerts (Priority: P3)
As an auditor, I want to fetch alert details for investigation.

**Why this priority**: Investigation requires traceability.

**Independent Test**: Query /alerts/{id} and verify decision and reasons are returned.

**Acceptance Scenarios**:
1. **Given** an existing alert, **When** I request it by ID, **Then** I see the decision and reasons.

### Edge Cases
- Event replay with same eventId.
- Missing required event fields.
- Threshold changes mid-stream.

## Functional Requirements
- FR-01: Ingest transaction events for scoring.
- FR-02: Compute risk score using rule sets and sliding windows.
- FR-03: Block transactions above threshold and emit alerts.
- FR-04: Persist alerts and decision reasons.
- FR-05: Expose /api/v1/health.

## Non-Functional Requirements
- NFR-01: p95 event ingestion < 120ms in local env.
- NFR-02: 500 events/sec in local env.
- NFR-03: Structured JSON logs with traceId and eventId.
- NFR-04: OpenTelemetry traces for ingestion and scoring.
- NFR-05: 100% Docker local environment.
- NFR-06: API versioned under /api/v1.

## Success Criteria
- SC-01: High-risk events are blocked with explicit reasons.
- SC-02: p95 ingestion latency remains under 120ms in local tests.
- SC-03: Alert queries return full decision context.

## API Contracts
- OpenAPI: .specify/specs/001-fraud-detection/contracts/openapi.yaml

## Roadmap
- Milestone 1: Event ingestion + basic rules.
- Milestone 2: Sliding windows + persistence.
- Milestone 3: Observability + security hardening.
- Milestone 4: Manual review workflow.

## Trade-offs
- Stream processing vs batch.
- Fixed vs dynamic thresholds.
- Rule complexity vs performance.
- Auto-block vs manual review.
- Full persistence vs critical events only.
