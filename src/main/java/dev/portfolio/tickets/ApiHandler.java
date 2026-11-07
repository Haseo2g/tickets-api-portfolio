package dev.portfolio.tickets;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static class ClientHolder { static final DynamoDbClient DB = DynamoDbClient.create(); }
    private final TicketService service;
    public ApiHandler() { this(new TicketService(new DynamoTicketRepository(ClientHolder.DB, System.getenv("TABLE_NAME")), Clock.systemUTC())); }
    public ApiHandler(TicketService service) { this.service = service; }

    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        long started = System.nanoTime();
        String requestId = context != null && context.getAwsRequestId() != null ? context.getAwsRequestId() : UUID.randomUUID().toString();
        String method = "", path = ""; int status = 500; String ticketId = null, type = "request";
        try {
            @SuppressWarnings("unchecked") Map<String, Object> rc = (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
            @SuppressWarnings("unchecked") Map<String, Object> http = (Map<String, Object>) rc.getOrDefault("http", Map.of());
            method = String.valueOf(http.getOrDefault("method", ""));
            path = String.valueOf(event.getOrDefault("rawPath", ""));
            @SuppressWarnings("unchecked") Map<String, String> query = (Map<String, String>) event.getOrDefault("queryStringParameters", Map.of());
            if (query == null) query = Map.of();
            Object data;
            if (method.equals("GET") && path.equals("/health")) { status = 200; data = Map.of("status", "ok"); }
            else if (method.equals("POST") && path.equals("/tickets")) {
                JsonNode body = body(event, "title", "description");
                Ticket t = service.create(string(body, "title"), string(body, "description"));
                ticketId = t.id(); status = 201; data = t;
            } else if (method.equals("GET") && path.equals("/tickets")) {
                int limit;
                try { limit = Integer.parseInt(query.getOrDefault("limit", "20")); }
                catch (NumberFormatException e) { throw new ApiException(400, "VALIDATION_ERROR", "limit inválido"); }
                data = service.list(limit, query.get("nextToken")); status = 200;
            } else if (path.matches("/tickets/[0-9a-fA-F-]{36}(/status)?")) {
                ticketId = path.substring(9, 45);
                if (method.equals("GET") && path.length() == 45) { data = service.get(ticketId); status = 200; }
                else if (method.equals("PATCH") && path.endsWith("/status")) {
                    JsonNode body = body(event, "status"); String value = string(body, "status");
                    Ticket.Status next;
                    try { next = Ticket.Status.valueOf(value); }
                    catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "status inválido"); }
                    data = service.changeStatus(ticketId, next); status = 200;
                } else throw new ApiException(404, "NOT_FOUND", "Rota não encontrada");
            } else throw new ApiException(404, "NOT_FOUND", "Rota não encontrada");
            return response(status, data, requestId);
        } catch (ApiException e) {
            status = e.status; type = e.code;
            return response(status, Map.of("error", Map.of("code", e.code, "message", e.getMessage(), "requestId", requestId)), requestId);
        } catch (Exception e) {
            status = 500; type = "UNEXPECTED_ERROR";
            return response(500, Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", "Erro interno", "requestId", requestId)), requestId);
        } finally {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("eventType", type); log.put("requestId", requestId); log.put("method", method); log.put("route", path);
            log.put("statusCode", status); log.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            if (ticketId != null) log.put("ticketId", ticketId);
            try { System.out.println(JSON.writeValueAsString(log)); } catch (JsonProcessingException ignored) { }
        }
    }
    private static JsonNode body(Map<String, Object> event, String... fields) {
        try {
            Object raw = event.get("body");
            if (!(raw instanceof String text)) throw new IllegalArgumentException();
            if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) text = new String(java.util.Base64.getDecoder().decode(text), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject() || node.size() != fields.length) throw new IllegalArgumentException();
            for (String field : fields) if (!node.has(field)) throw new IllegalArgumentException();
            return node;
        } catch (Exception e) { throw new ApiException(400, "VALIDATION_ERROR", "JSON inválido ou campos não permitidos"); }
    }
    private static String string(JsonNode body, String field) {
        if (!body.path(field).isTextual()) throw new ApiException(400, "VALIDATION_ERROR", field + " deve ser texto");
        return body.get(field).asText();
    }
    private static Map<String, Object> response(int status, Object body, String requestId) {
        try { return Map.of("statusCode", status, "headers", Map.of("content-type", "application/json; charset=utf-8", "x-request-id", requestId), "body", JSON.writeValueAsString(body)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
