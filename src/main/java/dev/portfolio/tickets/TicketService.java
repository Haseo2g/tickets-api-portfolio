package dev.portfolio.tickets;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class TicketService {
    public static final int MAX_TITLE = 120, MAX_DESCRIPTION = 2000, MAX_PAGE = 50;
    private final TicketRepository repository;
    private final Clock clock;
    public TicketService(TicketRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    public Ticket create(String title, String description) {
        validate(title, MAX_TITLE, "title"); validate(description, MAX_DESCRIPTION, "description");
        String now = Instant.now(clock).toString();
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), title.trim(), description.trim(), Ticket.Status.OPEN, now, now);
        repository.create(ticket); return ticket;
    }
    private static void validate(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw new ApiException(400, "VALIDATION_ERROR", field + " deve ter entre 1 e " + max + " caracteres");
    }
    public Ticket get(String id) { return repository.find(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Chamado não encontrado")); }
    public TicketRepository.Page list(int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE) throw new ApiException(400, "VALIDATION_ERROR", "limit deve estar entre 1 e 50");
        return repository.list(limit, cursor);
    }
    public Ticket changeStatus(String id, Ticket.Status next) {
        Ticket current = get(id);
        if (!((current.status() == Ticket.Status.OPEN && next == Ticket.Status.IN_PROGRESS) ||
              (current.status() == Ticket.Status.IN_PROGRESS && next == Ticket.Status.CLOSED)))
            throw new ApiException(409, "INVALID_TRANSITION", "Transição de status não permitida");
        if (!repository.changeStatus(id, current.status(), next, Instant.now(clock).toString())) {
            if (repository.find(id).isEmpty()) throw new ApiException(404, "NOT_FOUND", "Chamado não encontrado");
            throw new ApiException(409, "CONCURRENT_UPDATE", "Status alterado por outra requisição");
        }
        return get(id);
    }
}
