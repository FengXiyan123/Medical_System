package com.feng.medical.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/api/admin/audit-events")
public class AuditController {
    private final AuditEventRepository events;
    public AuditController(AuditEventRepository events) { this.events=events; }
    @GetMapping public List<AuditEvent> list(@RequestParam(required=false) UUID actorId,@RequestParam(required=false) String action,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,@RequestParam(required=false) Integer limit) {return events.find(actorId,action,from,to,limit==null?30:limit);}
}
