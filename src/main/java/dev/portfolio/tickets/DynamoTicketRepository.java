package dev.portfolio.tickets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class DynamoTicketRepository implements TicketRepository {
    private final DynamoDbClient db;
    private final String table;
    public DynamoTicketRepository(DynamoDbClient db, String table) { this.db = db; this.table = table; }
    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static Map<String, AttributeValue> key(String id) { return Map.of("id", s(id)); }
    private static Ticket ticket(Map<String, AttributeValue> item) {
        return new Ticket(item.get("id").s(), item.get("title").s(), item.get("description").s(), Ticket.Status.valueOf(item.get("status").s()), item.get("createdAt").s(), item.get("updatedAt").s());
    }
    public void create(Ticket t) {
        db.putItem(PutItemRequest.builder().tableName(table).conditionExpression("attribute_not_exists(id)").item(Map.of(
            "id", s(t.id()), "title", s(t.title()), "description", s(t.description()), "status", s(t.status().name()),
            "createdAt", s(t.createdAt()), "updatedAt", s(t.updatedAt()))).build());
    }
    public Optional<Ticket> find(String id) {
        Map<String, AttributeValue> item = db.getItem(GetItemRequest.builder().tableName(table).key(key(id)).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(ticket(item));
    }
    public Page list(int limit, String cursor) {
        Map<String, AttributeValue> start = null;
        if (cursor != null) {
            try {
                String id = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException();
                start = key(id);
            } catch (IllegalArgumentException e) { throw new ApiException(400, "VALIDATION_ERROR", "nextToken inválido"); }
        }
        ScanRequest.Builder request = ScanRequest.builder().tableName(table).limit(limit);
        if (start != null) request.exclusiveStartKey(start);
        ScanResponse response = db.scan(request.build());
        var items = new ArrayList<Ticket>(); response.items().forEach(item -> items.add(ticket(item)));
        String next = response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty() ? null :
            Base64.getUrlEncoder().withoutPadding().encodeToString(response.lastEvaluatedKey().get("id").s().getBytes(StandardCharsets.UTF_8));
        return new Page(items, next);
    }
    public boolean changeStatus(String id, Ticket.Status expected, Ticket.Status next, String updatedAt) {
        try {
            db.updateItem(UpdateItemRequest.builder().tableName(table).key(key(id))
                .conditionExpression("attribute_exists(id) AND #st = :expected")
                .updateExpression("SET #st = :next, updatedAt = :updated")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":expected", s(expected.name()), ":next", s(next.name()), ":updated", s(updatedAt))).build());
            return true;
        } catch (ConditionalCheckFailedException e) { return false; }
    }
}
