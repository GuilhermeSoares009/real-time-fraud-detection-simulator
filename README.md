# Real-Time Fraud Detection Simulator

Processador de eventos em tempo real que calcula score de risco e bloqueia atividades suspeitas com regras explicaveis.

## Capacidades-chave
- Scoring em estilo streaming com janelas deslizantes
- Alertas e decisoes de bloqueio
- Logs estruturados e tracing com OpenTelemetry
- Auditabilidade das decisoes de risco

## Inicio rapido (Docker)
```bash
docker compose up --build
```

- Healthcheck: http://localhost:8081/api/v1/health

## Contratos de API
- OpenAPI: docs/api/openapi.yaml

## Documentacao
- Project Reference Guide: PROJECT_REFERENCE_GUIDE.md
- Especificacoes: docs/specs/spec.md
- Plano tecnico: docs/specs/plan.md
- Tarefas: docs/specs/tasks.md
- ADRs: docs/adr/
- Trade-offs: docs/trade-offs.md
- Threat model: docs/threat-model.md
- Performance budget: docs/performance-budget.md
- Feature flags: docs/feature-flags.md
- Legacy spec (arquivado): docs/legacy-spec/
