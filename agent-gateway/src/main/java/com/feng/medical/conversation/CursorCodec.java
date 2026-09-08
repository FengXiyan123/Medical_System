package com.feng.medical.conversation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class CursorCodec {
    private CursorCodec() {
    }

    static String encode(Instant createdAt, UUID id) {
        String value = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static CursorValue decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            return new CursorValue(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("分页游标无效", exception);
        }
    }

    record CursorValue(Instant createdAt, UUID id) {
    }
}
