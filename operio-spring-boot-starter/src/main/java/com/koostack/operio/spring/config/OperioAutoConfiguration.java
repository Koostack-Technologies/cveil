package com.koostack.operio.spring.config;

import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.ConsentService;
import com.koostack.operio.spring.aspect.SensitiveAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OperioAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PluginRegistry<ConsentService> consentServiceRegistry() {
        return new PluginRegistry<>();
    }

    @Bean
    @ConditionalOnMissingBean
    public SensitiveAspect sensitiveAspect(PluginRegistry<ConsentService> registry) {
        return new SensitiveAspect(registry);
    }
}