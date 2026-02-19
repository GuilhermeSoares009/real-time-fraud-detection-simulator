# API Contracts

## POST /api/v1/events
Request
```json
{
  "eventId": "evt-1",
  "accountId": "acc-9",
  "amount": 900.00,
  "merchant": "shop-1",
  "country": "BR"
}
```

Response
```json
{
  "status": "ACCEPTED"
}
```

## GET /api/v1/alerts/{id}
Response
```json
{
  "alertId": "al-1",
  "riskScore": 92,
  "decision": "BLOCK"
}
```
