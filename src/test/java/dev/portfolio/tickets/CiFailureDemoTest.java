package dev.portfolio.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CiFailureDemoTest {
    @Test
    void demoCiDetectsWrongInitialStatusExpectation() {
        // Falha intencional: a regra correta inicia todo chamado em OPEN.
        TicketService service = new TicketService(
                mock(TicketRepository.class),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        Ticket created = service.create("Demonstração de CI", "Dado fictício");

        assertEquals(Ticket.Status.CLOSED, created.status(),
                "DEMO INTENCIONAL: expectativa errada para comprovar a reprovação do CI");
    }
}
