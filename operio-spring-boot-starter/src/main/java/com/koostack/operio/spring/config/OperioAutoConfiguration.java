package com.koostack.operio.spring.config;

import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.spring.aspect.SensitiveAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OperioAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PluginRegistry<ConsentDecisionService> consentServiceRegistry() {
        return new PluginRegistry<>();
    }

    @Bean
    @ConditionalOnMissingBean
    public SensitiveAspect sensitiveAspect(PluginRegistry<ConsentDecisionService> registry) {
        return new SensitiveAspect(registry);
    }
}