package com.feng.medical.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feng.medical.conversation.RunRepository;
import com.feng.medical.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class RunEventController {
    private final RunEventService events;
    private final SseRunEventBroadcaster broadcaster;
    private final RunRepository runs;
    private final ServiceCallbackTokenVerifier callbacks;
    private final ObjectMapper json;

    public RunEventController(RunEventService events, SseRunEventBroadcaster broadcaster, RunRepository runs, ServiceCallbackTokenVerifier callbacks, ObjectMapper json) {
        this.events = events; this.broadcaster = broadcaster; this.runs = runs; this.callbacks = callbacks; this.json = json;
    }

    @GetMapping(value = "/api/v1/conversations/{conversationId}/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId, @PathVariable UUID runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) throws Exception {
        requireOwned(user, conversationId, runId);
        long after = parseLastEventId(lastEventId);
        SseEmitter emitter = broadcaster.subscribe(runId);
        for (RunEvent event : events.replay(runId, after)) {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().wireName()).data(event.payload()));
            if (event.type().isTerminal()) { emitter.complete(); break; }
        }
        return emitter;
    }

    @DeleteMapping("/api/v1/conversations/{conversationId}/runs/{runId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId, @PathVariable UUID runId) {
        requireOwned(user, conversationId, runId);
        events.cancel(runId);
    }

    @PostMapping("/api/internal/v1/runs/{runId}/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void acceptCallback(@PathVariable UUID runId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                               @Valid @RequestBody SubmitRunEventRequest request) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new AccessDeniedException("缺少服务令牌");
        callbacks.verify(authorization.substring(7), runId.toString());
        try {
            events.accept(new RunEvent(request.eventId(), runId, request.sequence(), RunEventType.fromWireName(request.type()),
                    json.writeValueAsString(request.payload()), Instant.now()));
        } catch (Exception error) {
            throw new IllegalArgumentException("运行事件不是有效 JSON", error);
        }
    }

    private void requireOwned(AuthenticatedUser user, UUID conversationId, UUID runId) {
        if (user == null || runs.findOwned(user.id(), conversationId, runId) == null) throw new AccessDeniedException("运行不存在或无权访问");
    }
    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Math.max(0, Long.parseLong(value)); } catch (NumberFormatException error) { return 0; }
    }
    public record SubmitRunEventRequest(UUID eventId, @Min(1) long sequence, @NotBlank String type, Map<String, Object> payload) { }
}
