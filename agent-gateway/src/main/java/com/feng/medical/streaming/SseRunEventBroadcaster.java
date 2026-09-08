package com.feng.medical.streaming;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseRunEventBroadcaster implements RunEventBroadcaster {
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> values = subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        values.add(emitter);
        emitter.onCompletion(() -> values.remove(emitter));
        emitter.onTimeout(() -> values.remove(emitter));
        return emitter;
    }

    @Override public void publish(RunEvent event) {
        for (SseEmitter emitter : subscribers.getOrDefault(event.runId(), new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().wireName()).data(event.payload()));
                if (event.type().isTerminal()) emitter.complete();
            } catch (IOException error) {
                emitter.completeWithError(error);
            }
        }
    }
}
