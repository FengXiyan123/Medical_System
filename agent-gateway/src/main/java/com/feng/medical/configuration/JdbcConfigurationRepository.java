package com.feng.medical.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
@Repository class JdbcConfigurationRepository implements ConfigurationRepository {
    private final JdbcClient jdbc;
    JdbcConfigurationRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public void saveProfile(ModelProfile p){jdbc.sql("INSERT INTO model_profile (id,name,provider,model,region,endpoint,credential_ref,enabled) VALUES (:id,:name,:provider,:model,:region,:endpoint,:credentialRef,:enabled) ON DUPLICATE KEY UPDATE name=VALUES(name),provider=VALUES(provider),model=VALUES(model),region=VALUES(region),endpoint=VALUES(endpoint),credential_ref=VALUES(credential_ref),enabled=VALUES(enabled)").param("id",p.id().toString()).param("name",p.name()).param("provider",p.provider()).param("model",p.model()).param("region",p.region()).param("endpoint",p.endpoint()).param("credentialRef",p.credentialRef()).param("enabled",p.enabled()).update();}
    @Override public List<ModelProfile> profiles(){return jdbc.sql("SELECT id,name,provider,model,region,endpoint,credential_ref,enabled FROM model_profile ORDER BY name").query((rs,row)->new ModelProfile(UUID.fromString(rs.getString("id")),rs.getString("name"),rs.getString("provider"),rs.getString("model"),rs.getString("region"),rs.getString("endpoint"),rs.getString("credential_ref"),rs.getBoolean("enabled"))).list();}
    @Override public void savePrompt(PromptVersion p){jdbc.sql("INSERT INTO prompt_version (id,name,version,template,template_hash,status,created_at) VALUES (:id,:name,:version,:template,:hash,:status,:createdAt)").param("id",p.id().toString()).param("name",p.name()).param("version",p.version()).param("template",p.template()).param("hash",p.hash()).param("status",p.active()?"ACTIVE":"DRAFT").param("createdAt",Timestamp.from(p.createdAt())).update();}
    @Override public List<PromptVersion> prompts(){return jdbc.sql("SELECT id,name,version,template,template_hash,status,created_at FROM prompt_version ORDER BY name,version DESC").query((rs,row)->new PromptVersion(UUID.fromString(rs.getString("id")),rs.getString("name"),rs.getInt("version"),rs.getString("template"),rs.getString("template_hash"),"ACTIVE".equals(rs.getString("status")),rs.getTimestamp("created_at").toInstant())).list();}
    @Override public void savePolicy(RuntimePolicy p){jdbc.sql("INSERT INTO runtime_policy (id,policy_type,version,configuration,status,created_at) VALUES (:id,:type,:version,CAST(:configuration AS JSON),:status,:createdAt)").param("id",p.id().toString()).param("type",p.type()).param("version",p.version()).param("configuration",p.configuration()).param("status",p.active()?"ACTIVE":"DRAFT").param("createdAt",Timestamp.from(p.createdAt())).update();}
    @Override public List<RuntimePolicy> policies(){return jdbc.sql("SELECT id,policy_type,version,configuration,status,created_at FROM runtime_policy ORDER BY policy_type,version DESC").query((rs,row)->new RuntimePolicy(UUID.fromString(rs.getString("id")),rs.getString("policy_type"),rs.getInt("version"),rs.getString("configuration"),"ACTIVE".equals(rs.getString("status")),rs.getTimestamp("created_at").toInstant())).list();}
}
