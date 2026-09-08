package com.feng.medical.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisStreamIngestionEventPublisher implements IngestionEventPublisher {
    static final String STREAM = "medical:default:ingest.jobs";
    private final StringRedisTemplate redis; private final ObjectMapper objectMapper;
    RedisStreamIngestionEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) { this.redis = redis; this.objectMapper = objectMapper; }
    @Override public String publish(OutboxEvent event) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("eventId", event.id().toString()); values.put("eventType", event.eventType()); values.put("aggregateId", event.aggregateId().toString());
            values.putAll(objectMapper.readValue(event.payload(), new TypeReference<Map<String, String>>() { }));
            RecordId recordId = redis.opsForStream().add(StreamRecords.string(values).withStreamKey(STREAM));
            if (recordId == null) throw new IllegalStateException("Redis Stream 未返回记录标识");
            return recordId.getValue();
        } catch (Exception exception) { throw new IllegalStateException("文档任务投递 Redis Stream 失败", exception); }
    }
}
