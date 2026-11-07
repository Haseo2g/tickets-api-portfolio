package dev.portfolio.tickets;

import java.util.Optional;

public interface TicketRepository {
    void create(Ticket ticket);
    Optional<Ticket> find(String id);
    Page list(int limit, String cursor);
    // false means the item vanished or its status changed after the read.
    boolean changeStatus(String id, Ticket.Status expected, Ticket.Status next, String updatedAt);
    record Page(java.util.List<Ticket> items, String nextToken) {}
}
