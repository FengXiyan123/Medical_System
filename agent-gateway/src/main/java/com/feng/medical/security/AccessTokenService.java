package com.feng.medical.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Issues compact HS256 JWT access tokens. Refresh-token rotation is a separate persistence concern. */
public final class AccessTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AccessTokenService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("access token secret must contain at least 32 bytes");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("access token ttl must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
    }

    public String issue(AuthenticatedUser user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        String payload = encodeJson(Map.of(
                "sub", user.id().toString(),
                "username", user.username(),
                "role", user.role().name(),
                "auth_version", user.authVersion(),
                "iat", issuedAt.getEpochSecond(),
                "exp", expiresAt.getEpochSecond()));
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + encode(sign(signingInput));
    }

    public AccessTokenClaims verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            throw new InvalidAccessTokenException("invalid access token format");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] suppliedSignature;
        try {
            suppliedSignature = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new InvalidAccessTokenException("invalid access token signature");
        }
        if (!MessageDigest.isEqual(sign(signingInput), suppliedSignature)) {
            throw new InvalidAccessTokenException("invalid access token signature");
        }
        JsonNode payload = decodeJson(parts[1]);
        try {
            Instant expiresAt = Instant.ofEpochSecond(payload.required("exp").asLong());
            if (!expiresAt.isAfter(clock.instant())) {
                throw new InvalidAccessTokenException("access token has expired");
            }
            return new AccessTokenClaims(
                    java.util.UUID.fromString(payload.required("sub").asText()),
                    payload.required("username").asText(),
                    UserRole.valueOf(payload.required("role").asText()),
                    payload.required("auth_version").asInt(),
                    Instant.ofEpochSecond(payload.required("iat").asLong()),
                    expiresAt);
        } catch (InvalidAccessTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidAccessTokenException("invalid access token payload");
        }
    }

    private String encodeJson(Map<String, Object> payload) {
        try {
            return encode(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode access token payload", exception);
        }
    }

    private JsonNode decodeJson(String encodedPayload) {
        try {
            return objectMapper.readTree(BASE64_URL_DECODER.decode(encodedPayload));
        } catch (IllegalArgumentException | IOException exception) {
            throw new InvalidAccessTokenException("invalid access token payload");
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("cannot sign access token", exception);
        }
    }

    private String encode(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }
}
