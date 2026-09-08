package com.feng.medical.streaming;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The client must load the durable run snapshot instead of restarting a run. */
@ResponseStatus(value = HttpStatus.GONE, reason = "EVENT_HISTORY_EXPIRED")
public class EventHistoryExpiredException extends RuntimeException {
    public EventHistoryExpiredException() { super("EVENT_HISTORY_EXPIRED"); }
}
