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

- Inclui `docker-compose.yml` e Dockerfile(s).
- Healthcheck: http://localhost:8081/api/v1/health

## API (MVP)

- `GET /api/v1/health`
- `POST /api/v1/transactions`
- `GET /api/v1/alerts`

### Variaveis de ambiente

- `PORT` (default: 8080)
- `RATE_LIMIT_PER_MIN` (default: 120)

## Qualidade (pre-commit)
Este repositorio usa pre-commit para CR + auditoria ASVS (OWASP ASVS v5.0.0) antes de cada commit.

```bash
pip install pre-commit
pre-commit install
```

Para rodar manualmente:

```bash
pre-commit run --all-files
```
