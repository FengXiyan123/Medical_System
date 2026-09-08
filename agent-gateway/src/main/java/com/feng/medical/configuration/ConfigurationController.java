package com.feng.medical.configuration;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/api/admin") public class ConfigurationController {
 private final ConfigurationService configurations; public ConfigurationController(ConfigurationService configurations){this.configurations=configurations;}
 @GetMapping("/model-profiles") public List<ModelProfile> profiles(){return configurations.profiles();}
 @PostMapping("/model-profiles") @ResponseStatus(HttpStatus.CREATED) public void saveProfile(@RequestBody ModelProfile profile){configurations.saveProfile(profile);}
 @GetMapping("/prompts") public List<PromptVersion> prompts(){return configurations.prompts();}
 @PostMapping("/prompts") @ResponseStatus(HttpStatus.CREATED) public void savePrompt(@RequestBody PromptVersion prompt){configurations.savePrompt(prompt);}
 @GetMapping("/policies") public List<RuntimePolicy> policies(){return configurations.policies();}
 @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED) public void savePolicy(@RequestBody RuntimePolicy policy){configurations.savePolicy(policy);}
}
