# AGENTS.md

## Setup commands
- Install deps: `./mvnw -q -DskipTests package`
- Start dev server: `./mvnw spring-boot:run`
- Run tests: `./mvnw test`

## Code style
- Java 21, Spring Boot
- Constructor injection
- Logs estruturados JSON
- Nao usar reflection sem justificativa

## Arquitetura
- API /api/v1 para ingestao
- Pipeline de streaming com Kafka Streams
- Regras em camada de dominio

## Padrões de logging
- JSON com traceId, spanId, eventId, accountId

## Estrategia de testes
- Unitarios de regras
- Integracao com Testcontainers
- Tests de carga simples

## Regras de seguranca
- Validacao de eventos
- Rate limiting
- Sem dados sensiveis em logs

## Checklist de PR
- Testes e lint ok
- Docs atualizadas
- ADR quando mudar regras

## Diretrizes de performance
- p95 ingestao < 120ms
- backpressure com filas
