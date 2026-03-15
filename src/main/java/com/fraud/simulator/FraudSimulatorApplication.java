package com.fraud.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class FraudSimulatorApplication {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_RATE_LIMIT = 120;
    private static final long LATENCY_BUDGET_MS = 200;

    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AlertStore alerts;
    private final DecisionStore decisions;
    private final MetricsStore metrics;

    private FraudSimulatorApplication(
        ObjectMapper mapper,
        RateLimiter rateLimiter,
        AlertStore alerts,
        DecisionStore decisions,
        MetricsStore metrics
    ) {
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.alerts = alerts;
        this.decisions = decisions;
        this.metrics = metrics;
    }

    public static void main(String[] args) throws IOException {
        int port = readIntEnv("PORT", DEFAULT_PORT);
        int rateLimit = readIntEnv("RATE_LIMIT_PER_MIN", DEFAULT_RATE_LIMIT);

        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        RateLimiter limiter = new RateLimiter(rateLimit, Duration.ofMinutes(1));
        AlertStore alerts = new AlertStore();
        DecisionStore decisions = new DecisionStore();
        MetricsStore metrics = new MetricsStore();
        FraudSimulatorApplication app = new FraudSimulatorApplication(mapper, limiter, alerts, decisions, metrics);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/health", app::handleHealth);
        server.createContext("/api/v1/transactions", app::handleTransaction);
        server.createContext("/api/v1/alerts", app::handleAlerts);
        server.createContext("/api/v1/decisions", app::handleDecisions);
        server.createContext("/api/v1/metrics", app::handleMetrics);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Instant start = Instant.now();
        if (!allow(exchange)) {
            return;
        }
        writeJson(exchange, 200, new StatusResponse("ok"));
        log(exchange, new LogEntry("health check", newTraceId(), "", "", 200, LATENCY_BUDGET_MS, 0, false));
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        metrics.record("/api/v1/health", durationMs, durationMs > LATENCY_BUDGET_MS);
    }

    private void handleTransaction(HttpExchange exchange) throws IOException {
        Instant start = Instant.now();
        String traceId = newTraceId();

        if (!allow(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            log(exchange, new LogEntry("method not allowed", traceId, "", "", 405, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        TransactionRequest request;
        try {
            request = mapper.readValue(exchange.getRequestBody(), TransactionRequest.class);
        } catch (JsonProcessingException ex) {
            writeJson(exchange, 400, new ErrorResponse("invalid json"));
            log(exchange, new LogEntry("invalid request", traceId, "", "", 400, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        List<String> validationErrors = validate(request);
        if (!validationErrors.isEmpty()) {
            writeJson(exchange, 400, new ErrorResponse(String.join("; ", validationErrors)));
            log(exchange, new LogEntry("validation failed", traceId, request.transactionId, "", 400, LATENCY_BUDGET_MS, 0, false));
            return;
        }

        ScoringResult scoring = score(request);
        DecisionLog decisionLog = new DecisionLog(
            Instant.now(),
            request.transactionId,
            request.accountId,
            request.amount,
            request.currency,
            request.channel,
            scoring.score,
            scoring.decision,
            List.copyOf(scoring.rules),
            traceId
        );
        decisions.add(decisionLog);

        if ("block".equals(scoring.decision)) {
            alerts.add(new Alert(
                Instant.now(),
                request.transactionId,
                request.accountId,
                request.channel,
                request.currency,
                scoring.score,
                scoring.decision,
                List.copyOf(scoring.rules),
                traceId
            ));
        }

        TransactionResponse response = new TransactionResponse(
            request.transactionId,
            traceId,
            scoring.score,
            scoring.decision,
            scoring.rules
        );
        writeJson(exchange, 200, response);

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        metrics.record("/api/v1/transactions", durationMs, durationMs > LATENCY_BUDGET_MS);
        log(exchange, new LogEntry(
            "transaction scored",
            traceId,
            request.transactionId,
            scoring.decision,
            200,
            LATENCY_BUDGET_MS,
            durationMs,
            durationMs > LATENCY_BUDGET_MS
        ));
    }

    private void handleAlerts(HttpExchange exchange) throws IOException {
        Instant start = Instant.now();
        if (!allow(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            return;
        }
        String accountId = queryParam(exchange, "accountId");
        int limit = parseLimit(queryParam(exchange, "limit"), 50);
        writeJson(exchange, 200, new AlertsResponse(alerts.list(accountId, limit)));
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        metrics.record("/api/v1/alerts", durationMs, durationMs > LATENCY_BUDGET_MS);
    }

    private void handleDecisions(HttpExchange exchange) throws IOException {
        Instant start = Instant.now();
        if (!allow(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            return;
        }

        DecisionQuery query = new DecisionQuery(
            queryParam(exchange, "transactionId"),
            queryParam(exchange, "accountId"),
            queryParam(exchange, "decision"),
            parseLimit(queryParam(exchange, "limit"), 100)
        );
        writeJson(exchange, 200, new DecisionsResponse(decisions.list(query)));
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        metrics.record("/api/v1/decisions", durationMs, durationMs > LATENCY_BUDGET_MS);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!allow(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, new ErrorResponse("method not allowed"));
            return;
        }
        writeJson(exchange, 200, metrics.snapshot());
    }

    private boolean allow(HttpExchange exchange) throws IOException {
        String key = clientIp(exchange);
        if (!rateLimiter.allow(key)) {
            writeJson(exchange, 429, new ErrorResponse("rate limit exceeded"));
            return false;
        }
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] data = mapper.writeValueAsBytes(payload);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void log(HttpExchange exchange, LogEntry entry) throws IOException {
        byte[] data = mapper.writeValueAsBytes(entry);
        System.out.println(new String(data));
    }

    private List<String> validate(TransactionRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.transactionId == null || request.transactionId.isBlank()) {
            errors.add("transactionId is required");
        }
        if (request.accountId == null || request.accountId.isBlank()) {
            errors.add("accountId is required");
        }
        if (request.amount <= 0) {
            errors.add("amount must be > 0");
        }
        if (request.currency == null || request.currency.isBlank()) {
            errors.add("currency is required");
        }
        return errors;
    }

    private ScoringResult score(TransactionRequest request) {
        double score = 10;
        List<String> rules = new ArrayList<>();

        if (request.amount >= 1000) {
            score += 70;
            rules.add("high-amount");
        }
        if ("card-not-present".equalsIgnoreCase(request.channel)) {
            score += 30;
            rules.add("card-not-present");
        }

        String decision = score >= 80 ? "block" : "allow";
        if (rules.isEmpty()) {
            rules.add("baseline");
        }
        return new ScoringResult(score, decision, rules);
    }

    private String clientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    private static int readIntEnv(String key, int fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record StatusResponse(String status) {}

    private record ErrorResponse(String error) {}

    private record TransactionRequest(
        String transactionId,
        String accountId,
        double amount,
        String currency,
        String channel
    ) {}

    private record TransactionResponse(
        String transactionId,
        String traceId,
        double score,
        String decision,
        List<String> rules
    ) {}

    private record Alert(
        Instant timestamp,
        String transactionId,
        String accountId,
        String channel,
        String currency,
        double score,
        String decision,
        List<String> rules,
        String traceId
    ) {}

    private record AlertsResponse(List<Alert> alerts) {}

    private record DecisionLog(
        Instant timestamp,
        String transactionId,
        String accountId,
        double amount,
        String currency,
        String channel,
        double score,
        String decision,
        List<String> rules,
        String traceId
    ) {}

    private record DecisionQuery(String transactionId, String accountId, String decision, int limit) {}

    private record DecisionsResponse(List<DecisionLog> decisions) {}

    private record RouteMetrics(String route, long requests, long budgetExceeded, long avgDurationMs) {}

    private record MetricsResponse(Instant generatedAt, List<RouteMetrics> routes) {}

    private record LogEntry(
        String message,
        String traceId,
        String transactionId,
        String decision,
        int status,
        long budgetMs,
        long durationMs,
        boolean budgetExceeded
    ) {}

    private record ScoringResult(double score, String decision, List<String> rules) {}

    private static final class AlertStore {
        private final CopyOnWriteArrayList<Alert> alerts = new CopyOnWriteArrayList<>();

        private void add(Alert alert) {
            alerts.add(alert);
        }

        private List<Alert> list(String accountId, int limit) {
            if (limit <= 0) {
                limit = 50;
            }
            List<Alert> result = new ArrayList<>(limit);
            for (int i = alerts.size() - 1; i >= 0 && result.size() < limit; i--) {
                Alert alert = alerts.get(i);
                if (accountId == null || accountId.isBlank() || accountId.equals(alert.accountId)) {
                    result.add(alert);
                }
            }
            return result;
        }
    }

    private static final class DecisionStore {
        private final CopyOnWriteArrayList<DecisionLog> decisions = new CopyOnWriteArrayList<>();

        private void add(DecisionLog decisionLog) {
            decisions.add(decisionLog);
            if (decisions.size() > 4000) {
                decisions.remove(0);
            }
        }

        private List<DecisionLog> list(DecisionQuery query) {
            int limit = query.limit <= 0 ? 100 : query.limit;
            List<DecisionLog> result = new ArrayList<>(limit);
            for (int i = decisions.size() - 1; i >= 0 && result.size() < limit; i--) {
                DecisionLog decision = decisions.get(i);
                if (query.transactionId != null && !query.transactionId.isBlank() && !query.transactionId.equals(decision.transactionId)) {
                    continue;
                }
                if (query.accountId != null && !query.accountId.isBlank() && !query.accountId.equals(decision.accountId)) {
                    continue;
                }
                if (query.decision != null && !query.decision.isBlank() && !query.decision.equalsIgnoreCase(decision.decision)) {
                    continue;
                }
                result.add(decision);
            }
            return result;
        }
    }

    private static final class MetricsStore {
        private final ConcurrentHashMap<String, RouteBucket> routes = new ConcurrentHashMap<>();

        private void record(String route, long durationMs, boolean budgetExceeded) {
            RouteBucket bucket = routes.computeIfAbsent(route, ignored -> new RouteBucket());
            synchronized (bucket) {
                bucket.requests++;
                bucket.totalDurationMs += durationMs;
                if (budgetExceeded) {
                    bucket.budgetExceeded++;
                }
            }
        }

        private MetricsResponse snapshot() {
            List<RouteMetrics> metrics = new ArrayList<>();
            for (Map.Entry<String, RouteBucket> entry : routes.entrySet()) {
                RouteBucket bucket = entry.getValue();
                synchronized (bucket) {
                    long avg = bucket.requests == 0 ? 0 : bucket.totalDurationMs / bucket.requests;
                    metrics.add(new RouteMetrics(entry.getKey(), bucket.requests, bucket.budgetExceeded, avg));
                }
            }
            return new MetricsResponse(Instant.now(), metrics);
        }

        private static final class RouteBucket {
            private long requests;
            private long budgetExceeded;
            private long totalDurationMs;
        }
    }

    private static final class RateLimiter {
        private final int maxRequests;
        private final Duration window;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        private RateLimiter(int maxRequests, Duration window) {
            this.maxRequests = maxRequests;
            this.window = window;
        }

        private boolean allow(String key) {
            Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
            synchronized (bucket) {
                Instant now = Instant.now();
                if (bucket.windowStart == null || Duration.between(bucket.windowStart, now).compareTo(window) >= 0) {
                    bucket.windowStart = now;
                    bucket.count = 0;
                }
                if (bucket.count >= maxRequests) {
                    return false;
                }
                bucket.count++;
                return true;
            }
        }

        private static final class Bucket {
            private Instant windowStart;
            private int count;
        }
    }
}
