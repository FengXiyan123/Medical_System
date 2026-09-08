package com.feng.medical.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import com.feng.medical.streaming.ServiceTokenIssuer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Gateway-to-Core adapter.  The gateway supplies only its freshly resolved scope. */
@Component
class HttpRetrievalTestPort implements RetrievalTestPort {
    private final RestClient client;
    private final ServiceTokenIssuer tokens;

    HttpRetrievalTestPort(AgentCoreKnowledgeProperties properties, ServiceTokenIssuer tokens) {
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(new SimpleClientHttpRequestFactory()).build();
        this.tokens = tokens;
    }

    @Override
    public RetrievalTestResult execute(RetrievalTestCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", command.query());
        payload.put("mode", command.scope().mode());
        payload.put("requested_knowledge_base_ids", command.requestedKnowledgeBaseIds());
        payload.put("context_token_limit", command.contextTokenLimit());
        payload.put("scope", command.scope());
        try {
            return client.post().uri("/internal/retrieval-tests").header("Authorization", "Bearer " + tokens.coreTicket("retrieval:execute")).body(payload).retrieve()
                    .body(RetrievalTestResult.class);
        } catch (RestClientResponseException error) {
            throw new IllegalStateException("检索测试服务请求失败: " + error.getStatusCode().value(), error);
        }
    }
}
