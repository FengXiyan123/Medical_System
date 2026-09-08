package com.feng.medical.configuration;
import java.util.List;
public interface ConfigurationRepository { void saveProfile(ModelProfile profile); List<ModelProfile> profiles(); void savePrompt(PromptVersion prompt); List<PromptVersion> prompts(); void savePolicy(RuntimePolicy policy); List<RuntimePolicy> policies(); }
