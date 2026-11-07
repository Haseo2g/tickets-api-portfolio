package dev.portfolio.tickets;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

class TicketApiTest {
    static class Memory implements TicketRepository {
        final Map<String, Ticket> data = new LinkedHashMap<>();
        public void create(Ticket t) { data.put(t.id(), t); }
        public Optional<Ticket> find(String id) { return Optional.ofNullable(data.get(id)); }
        public Page list(int limit, String cursor) {
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            List<Ticket> all = new ArrayList<>(data.values()); int end = Math.min(start + limit, all.size());
            return new Page(all.subList(start, end), end == all.size() ? null : String.valueOf(end));
        }
        public boolean changeStatus(String id, Ticket.Status expected, Ticket.Status next, String time) {
            Ticket old = data.get(id);
            if (old == null || old.status() != expected) return false;
            data.put(id, new Ticket(id, old.title(), old.description(), next, old.createdAt(), time)); return true;
        }
    }
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static Map<String,Object> event(String method, String path, String body, Map<String,String> query) {
        Map<String,Object> e = new HashMap<>(); e.put("requestContext", Map.of("http", Map.of("method", method)));
        e.put("rawPath", path); if (body != null) e.put("body", body); if (query != null) e.put("queryStringParameters", query); return e;
    }
    private static int code(Map<String,Object> response) { return (int) response.get("statusCode"); }
    @Test void creationValidationAndMissing() {
        Memory repo = new Memory(); TicketService service = new TicketService(repo, CLOCK);
        Ticket t = service.create(" Título ", " Descrição ");
        assertEquals(Ticket.Status.OPEN, t.status()); assertEquals("Título", t.title()); assertEquals("2026-01-01T00:00:00Z", t.createdAt());
        assertThrows(ApiException.class, () -> service.create(" ", "ok"));
        assertThrows(ApiException.class, () -> service.create("x".repeat(121), "ok"));
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(UUID.randomUUID().toString())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> service.changeStatus(UUID.randomUUID().toString(), Ticket.Status.IN_PROGRESS)).status);
    }
    @Test void transitionsAndConcurrentConflict() {
        Memory repo = new Memory(); TicketService service = new TicketService(repo, CLOCK); Ticket t = service.create("a", "b");
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeStatus(t.id(), Ticket.Status.CLOSED)).status);
        assertEquals(Ticket.Status.IN_PROGRESS, service.changeStatus(t.id(), Ticket.Status.IN_PROGRESS).status());
        assertEquals(Ticket.Status.CLOSED, service.changeStatus(t.id(), Ticket.Status.CLOSED).status());
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeStatus(t.id(), Ticket.Status.OPEN)).status);
        TicketRepository conflict = new Memory() { @Override public boolean changeStatus(String id, Ticket.Status expected, Ticket.Status next, String time) { return false; } };
        conflict.create(t); assertEquals("CONCURRENT_UPDATE", assertThrows(ApiException.class, () -> new TicketService(conflict, CLOCK).changeStatus(t.id(), Ticket.Status.IN_PROGRESS)).code);
    }
    @Test void paginationAndLimits() {
        TicketService service = new TicketService(new Memory(), CLOCK);
        service.create("a", "b"); service.create("c", "d"); service.create("e", "f");
        TicketRepository.Page first = service.list(2, null); assertEquals(2, first.items().size());
        assertEquals(1, service.list(2, first.nextToken()).items().size());
        assertEquals(400, assertThrows(ApiException.class, () -> service.list(51, null)).status);
    }
    @Test void httpErrorsAndUnexpectedFailure() {
        Memory repo = new Memory(); ApiHandler api = new ApiHandler(new TicketService(repo, CLOCK));
        assertEquals(400, code(api.handleRequest(event("POST", "/tickets", "{", null), null)));
        assertEquals(400, code(api.handleRequest(event("POST", "/tickets", "{\"title\":\"a\",\"description\":null}", null), null)));
        assertEquals(404, code(api.handleRequest(event("GET", "/tickets/" + UUID.randomUUID(), null, null), null)));
        assertEquals(400, code(api.handleRequest(event("GET", "/tickets", null, Map.of("limit", "abc")), null)));
        Ticket t = repo.data.values().stream().findFirst().orElseGet(() -> new TicketService(repo, CLOCK).create("a", "b"));
        assertEquals(400, code(api.handleRequest(event("PATCH", "/tickets/" + t.id() + "/status", "{\"status\":\"INVALID\"}", null), null)));
        assertEquals(200, code(api.handleRequest(event("GET", "/health", null, null), null)));
        TicketRepository broken = new Memory() { @Override public Optional<Ticket> find(String id) { throw new IllegalStateException("secret"); } };
        Map<String,Object> result = new ApiHandler(new TicketService(broken, CLOCK)).handleRequest(event("GET", "/tickets/" + UUID.randomUUID(), null, null), null);
        assertEquals(500, code(result)); assertFalse(result.get("body").toString().contains("secret"));
        assertTrue(result.get("body").toString().contains("requestId"));
    }
}
