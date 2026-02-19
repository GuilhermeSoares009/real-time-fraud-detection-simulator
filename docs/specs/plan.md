# Plan: Real-Time Fraud Detection Simulator

## Architecture
- Spring Boot service for ingestion and alert APIs.
- Kafka Streams (or in-memory simulation) for scoring pipeline.
- Postgres for alerts and decisions.
- Redis for rule cache.

## Rules and Windows
- Configurable rules with weights and thresholds.
- Sliding window aggregation for velocity checks.

## Observability
- JSON logs with traceId, spanId, eventId, accountId.
- OpenTelemetry tracing for pipeline stages.

## Security
- Input validation at ingress.
- Rate limiting on /events.
- No sensitive data in logs.

## Feature Flags
- dynamic_threshold_enabled
- manual_review_flow_enabled
- ruleset_v2_enabled

## Local Dev and CI
- Docker Compose for Postgres, Redis, Kafka.
- CI runs unit and integration tests (Testcontainers).

## ADRs
- Streaming approach
- Explainable rule engine
