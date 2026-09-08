package com.feng.medical.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ServiceTokenIssuer {
    private final byte[] secret; private final ObjectMapper mapper; private final Clock clock;
    public ServiceTokenIssuer(ServiceCallbackProperties properties, ObjectMapper mapper, Clock clock) { this.secret=(properties.secret()==null?"":properties.secret()).getBytes(StandardCharsets.UTF_8); this.mapper=mapper; this.clock=clock; }
    public String runTicket(String runId, String userId, String mode) { return issue("agent-core", "runs:write", runId, userId, mode); }
    public String coreTicket(String scope) { return issue("agent-core", scope, null, null, null); }
    private String issue(String audience,String scope,String runId,String userId,String mode) {
        try { if(secret.length<32) throw new IllegalStateException("服务令牌密钥未配置"); Map<String,Object> claims=new LinkedHashMap<>(); claims.put("sub","agent-gateway".equals(audience)?"agent-core":"agent-gateway"); claims.put("aud",audience); claims.put("scope",scope); claims.put("run_id",runId); if(userId!=null) claims.put("user_id",userId); if(mode!=null) claims.put("mode",mode); claims.put("exp",clock.instant().getEpochSecond()+300); String header=enc(Map.of("alg","HS256","typ","JWT")); String payload=enc(claims); Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return header+"."+payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((header+"."+payload).getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException("无法签发服务令牌",e);} }
    private String enc(Object value) throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value)); }
}
