package com.feng.medical.streaming;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies the short-lived callback JWT issued to Agent Core; it never accepts a browser access token. */
@Component
public class ServiceCallbackTokenVerifier {
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ServiceCallbackTokenVerifier(ServiceCallbackProperties properties, ObjectMapper mapper, Clock clock) {
        String configured = properties.secret();
        this.secret = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper; this.clock = clock;
    }

    public void verify(String token, String expectedRunId) {
        try {
            if (secret.length < 32) throw new IllegalArgumentException("服务回调密钥未配置");
            String[] pieces = token.split("\\.");
            if (pieces.length != 3 || !constantTimeEquals(sign(pieces[0] + "." + pieces[1]), decode(pieces[2]))) throw new IllegalArgumentException("服务令牌签名无效");
            Map<String, Object> header = decodeJson(pieces[0]); Map<String, Object> claims = decodeJson(pieces[1]);
            if (!"HS256".equals(header.get("alg")) || !"agent-core".equals(claims.get("sub")) || !"agent-gateway".equals(claims.get("aud"))) throw new IllegalArgumentException("服务令牌声明无效");
            if (!(claims.get("exp") instanceof Number expiry) || expiry.longValue() <= clock.instant().getEpochSecond()) throw new IllegalArgumentException("服务令牌已过期");
            if (!expectedRunId.equals(claims.get("run_id")) || !hasCallbackScope(claims.get("scope"))) throw new IllegalArgumentException("服务令牌无权回调此运行");
        } catch (IllegalArgumentException error) { throw error;
        } catch (Exception error) { throw new IllegalArgumentException("服务令牌无效", error); }
    }

    /** Verifies Agent Core's scope-resolve credential; browser JWTs never reach this path. */
    public void verifyKnowledgeScope(String token, String expectedRunId) {
        try {
            if (secret.length < 32) throw new IllegalArgumentException("服务回调密钥未配置");
            String[] pieces = token.split("\\.");
            if (pieces.length != 3 || !constantTimeEquals(sign(pieces[0] + "." + pieces[1]), decode(pieces[2]))) throw new IllegalArgumentException("服务令牌签名无效");
            Map<String, Object> header = decodeJson(pieces[0]); Map<String, Object> claims = decodeJson(pieces[1]);
            if (!"HS256".equals(header.get("alg")) || !"agent-core".equals(claims.get("sub")) || !"agent-gateway".equals(claims.get("aud"))) throw new IllegalArgumentException("服务令牌声明无效");
            if (!(claims.get("exp") instanceof Number expiry) || expiry.longValue() <= clock.instant().getEpochSecond()) throw new IllegalArgumentException("服务令牌已过期");
            if (!expectedRunId.equals(claims.get("run_id")) || !hasKnowledgeScope(claims.get("scope"))) throw new IllegalArgumentException("服务令牌无权解析此运行范围");
        } catch (IllegalArgumentException error) { throw error;
        } catch (Exception error) { throw new IllegalArgumentException("服务令牌无效", error); }
    }

    /** Callback used by the trusted Agent Core ingestion worker. */
    public void verifyIngestionCallback(String token) {
        verifyScope(token, "ingestion:callback");
    }

    private void verifyScope(String token, String expectedScope) {
        try {
            if (secret.length < 32) throw new IllegalArgumentException("服务回调密钥未配置");
            String[] pieces = token.split("\\.");
            if (pieces.length != 3 || !constantTimeEquals(sign(pieces[0] + "." + pieces[1]), decode(pieces[2]))) throw new IllegalArgumentException("服务令牌签名无效");
            Map<String, Object> header = decodeJson(pieces[0]); Map<String, Object> claims = decodeJson(pieces[1]);
            if (!"HS256".equals(header.get("alg")) || !"agent-core".equals(claims.get("sub")) || !"agent-gateway".equals(claims.get("aud"))) throw new IllegalArgumentException("服务令牌声明无效");
            if (!(claims.get("exp") instanceof Number expiry) || expiry.longValue() <= clock.instant().getEpochSecond()) throw new IllegalArgumentException("服务令牌已过期");
            if (!(claims.get("scope") instanceof String scopes) || !java.util.Arrays.asList(scopes.split(" ")).contains(expectedScope)) throw new IllegalArgumentException("服务令牌无权执行此回调");
        } catch (IllegalArgumentException error) { throw error;
        } catch (Exception error) { throw new IllegalArgumentException("服务令牌无效", error); }
    }

    private Map<String, Object> decodeJson(String value) throws Exception { return mapper.readValue(decode(value), new TypeReference<>() { }); }
    private byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private byte[] sign(String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
    private boolean constantTimeEquals(byte[] left, byte[] right) { return java.security.MessageDigest.isEqual(left, right); }
    private boolean hasCallbackScope(Object value) { return value instanceof String scopes && java.util.Arrays.asList(scopes.split(" ")).contains("runs:callback"); }
    private boolean hasKnowledgeScope(Object value) { return value instanceof String scopes && java.util.Arrays.asList(scopes.split(" ")).contains("knowledge:scope"); }
}
