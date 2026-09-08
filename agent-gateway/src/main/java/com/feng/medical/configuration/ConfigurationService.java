package com.feng.medical.configuration;

import java.util.List;
import org.springframework.stereotype.Service;
@Service public class ConfigurationService {
    private final ConfigurationRepository repository;
    public ConfigurationService(ConfigurationRepository repository) { this.repository=repository; }
    public void saveProfile(ModelProfile profile) { if(profile.credentialRef()==null || !profile.credentialRef().matches("[A-Za-z][A-Za-z0-9+.-]*://[^\\s]+") || profile.credentialRef().contains("sk-")) throw new IllegalArgumentException("模型档案只能保存 credential_ref，不能保存实际密钥"); repository.saveProfile(profile); }
    public List<ModelProfile> profiles() { return repository.profiles(); }
    public void savePrompt(PromptVersion prompt) { if(prompt.template()==null||prompt.template().isBlank())throw new IllegalArgumentException("提示词不能为空");repository.savePrompt(prompt); }
    public List<PromptVersion> prompts(){return repository.prompts();}
    public void savePolicy(RuntimePolicy policy){if(!"BUDGET".equals(policy.type())&&!"RETRIEVAL".equals(policy.type()))throw new IllegalArgumentException("策略类型必须是 BUDGET 或 RETRIEVAL");repository.savePolicy(policy);}
    public List<RuntimePolicy> policies(){return repository.policies();}
}
