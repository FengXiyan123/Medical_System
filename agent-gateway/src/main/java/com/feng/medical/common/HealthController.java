package com.feng.medical.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/actuator/health/liveness")
    public Map<String, String> liveness() {
        return Map.of("status", "UP");
    }
}
