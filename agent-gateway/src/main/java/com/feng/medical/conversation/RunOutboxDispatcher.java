package com.feng.medical.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feng.medical.knowledge.AgentCoreKnowledgeProperties;
import com.feng.medical.streaming.ServiceTokenIssuer;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
class RunOutboxDispatcher {
 private final JdbcClient jdbc; private final ObjectMapper json; private final ServiceTokenIssuer tokens; private final RestClient core;
 RunOutboxDispatcher(JdbcClient jdbc,ObjectMapper json,ServiceTokenIssuer tokens,AgentCoreKnowledgeProperties props){this.jdbc=jdbc;this.json=json;this.tokens=tokens;this.core=RestClient.builder().baseUrl(props.baseUrl()).requestFactory(new SimpleClientHttpRequestFactory()).build();}
 @Scheduled(fixedDelayString="${app.runs.outbox-delay-ms:500}") void dispatch(){ for(Map<String,String> event:jdbc.sql("SELECT id,payload FROM outbox_event WHERE dispatched_at IS NULL AND event_type='RUN_QUEUED' ORDER BY created_at LIMIT 20").query((rs,n)->Map.of("id",rs.getString("id"),"payload",rs.getString("payload"))).list()) try { Map<String,Object> p=json.readValue(event.get("payload"),new TypeReference<>(){}); Object selected=p.get("selectedKnowledgeBaseIds"); Map<String,Object> request=Map.of("question",p.get("question"),"mode",p.get("mode"),"knowledge_base_ids",selected); Map<String,Object> body=Map.of("run_id",p.get("runId"),"user_id",p.get("userId"),"request",request,"authorized_knowledge_base_ids",selected); core.post().uri("/internal/runs").header("Authorization","Bearer "+tokens.runTicket((String)p.get("runId"),(String)p.get("userId"),(String)p.get("mode"))).body(body).retrieve().toBodilessEntity(); jdbc.sql("UPDATE outbox_event SET dispatched_at=CURRENT_TIMESTAMP(3),delivery_attempts=delivery_attempts+1,last_error=NULL WHERE id=:id").param("id",event.get("id")).update(); } catch(Exception e){String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); jdbc.sql("UPDATE outbox_event SET delivery_attempts=delivery_attempts+1,last_error=:e WHERE id=:id").param("id",event.get("id")).param("e",message.substring(0,Math.min(500,message.length()))).update();} }
}
