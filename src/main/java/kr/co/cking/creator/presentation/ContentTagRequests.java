package kr.co.cking.creator.presentation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import java.util.Map;

/** JsonNode numbers retain their wire type; service rejects fractional/coerced IDs. */
public final class ContentTagRequests {
    private ContentTagRequests() {}
    public interface Request {
        default Map<String,Object> fields() {
            return JsonMapper.builder().build().convertValue(this,new TypeReference<Map<String,Object>>() {});
        }
    }
    public record Automatic(JsonNode creatorId,JsonNode inputRevision,JsonNode inputHash,
            JsonNode automaticRevision,JsonNode idempotencyKey,JsonNode createdAt,JsonNode origin,
            JsonNode creatorConfirmed,JsonNode provenance,JsonNode record) implements Request {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key,JsonNode value) { throw new IllegalArgumentException("Unknown automatic payload member: "+key); }
    }
    public record Claim(JsonNode leaseSeconds) implements Request {}
    public record Lease(JsonNode generation,JsonNode leaseToken) implements Request {}
    public record Ack(JsonNode generation,JsonNode leaseToken,JsonNode status,JsonNode summary) implements Request {}
    public record Reconcile(JsonNode inputs) implements Request {}
    public record Confirmation(JsonNode automaticRevision,JsonNode inputHash,JsonNode parents,JsonNode topics) implements Request {}
}
