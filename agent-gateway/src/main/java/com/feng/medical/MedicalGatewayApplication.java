package com.feng.medical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.feng.medical.auth.RefreshTokenProperties;
import com.feng.medical.security.AccessTokenProperties;
import com.feng.medical.file.StorageProperties;
import com.feng.medical.knowledge.AgentCoreKnowledgeProperties;
import com.feng.medical.streaming.ServiceCallbackProperties;
import com.feng.medical.user.DemoBootstrapProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AccessTokenProperties.class, RefreshTokenProperties.class, StorageProperties.class,
        AgentCoreKnowledgeProperties.class, ServiceCallbackProperties.class, DemoBootstrapProperties.class})
public class MedicalGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicalGatewayApplication.class, args);
    }
}
