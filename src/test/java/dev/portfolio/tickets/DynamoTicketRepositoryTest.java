package dev.portfolio.tickets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoTicketRepositoryTest {
    @Test void updateRequiresExistingItemAndExpectedStatus() {
        DynamoDbClient db = mock(DynamoDbClient.class);
        DynamoTicketRepository repo = new DynamoTicketRepository(db, "tickets");
        assertTrue(repo.changeStatus("id", Ticket.Status.OPEN, Ticket.Status.IN_PROGRESS, "2026-01-01T00:00:00Z"));
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(db).updateItem(captor.capture());
        UpdateItemRequest request = captor.getValue();
        assertEquals("attribute_exists(id) AND #st = :expected", request.conditionExpression());
        assertEquals("OPEN", request.expressionAttributeValues().get(":expected").s());
        assertEquals("IN_PROGRESS", request.expressionAttributeValues().get(":next").s());
    }
    @Test void conditionalFailureIsReportedAsConflict() {
        DynamoDbClient db = mock(DynamoDbClient.class);
        when(db.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().message("race").build());
        assertFalse(new DynamoTicketRepository(db, "tickets").changeStatus("id", Ticket.Status.OPEN, Ticket.Status.IN_PROGRESS, "now"));
    }
}
