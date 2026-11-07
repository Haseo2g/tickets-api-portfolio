package dev.portfolio.tickets;

public record Ticket(String id, String title, String description, Status status, String createdAt, String updatedAt) {
    public enum Status { OPEN, IN_PROGRESS, CLOSED }
}
