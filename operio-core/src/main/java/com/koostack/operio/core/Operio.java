package com.koostack.operio.core;

import java.util.HashSet;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.ConsentService;
import com.koostack.operio.core.service.IdentityResolver;
import com.koostack.operio.core.service.MaskingStrategy;
import com.koostack.operio.core.service.SensitiveFieldResolver;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Operio {

    private final ConsentService consentService;
    private final IdentityResolver identityResolver;
    private final AuditLogger auditLogger;
    private final MaskingStrategy maskingStrategy;
    private final SensitiveFieldResolver sensitiveFieldResolver;


    public Operio(Builder builder) {
        this.consentService = builder.consentService;
        this.identityResolver = builder.identityResolver;
        this.auditLogger = builder.auditLogger;
        this.maskingStrategy = builder.maskingStrategy;
        this.sensitiveFieldResolver = builder.sensitiveFieldResolver;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        // We override the Lombok methods to accept Strings
        private ConsentService consentService;
        private IdentityResolver identityResolver;
        private AuditLogger auditLogger;
        private MaskingStrategy maskingStrategy;
        private SensitiveFieldResolver sensitiveFieldResolver;


        public Operio build() {
            return new Operio(consentService, identityResolver, auditLogger, maskingStrategy, sensitiveFieldResolver);
        }
        
        public Builder withConsentProvider(String name) {
            this.consentService = PluginRegistry.resolve(ConsentService.class, name);
            return this;
        }

        public Builder withIdentityProvider(String name) {
            this.identityResolver = PluginRegistry.resolve(IdentityResolver.class, name);
            return this;
        }

        public Builder withAuditProvider(String name) {
            this.auditLogger = PluginRegistry.resolve(AuditLogger.class, name);
            return this;
        }

        public Builder withMaskingProvider(String name) {
            this.maskingStrategy = PluginRegistry.resolve(MaskingStrategy.class, name);
            return this;
        }
        public Builder withSensitiveFieldResolver(String name) {
            this.sensitiveFieldResolver = PluginRegistry.resolve(SensitiveFieldResolver.class, name);
            return this;
        }
    }

    /**
     * High-level method to handle a data access request.
     */
    public Object access(Object rawData) {
        var identity = identityResolver.resolve();
        var scopes = sensitiveFieldResolver.resolveScopes(rawData);
        var response = consentService.evaluateConsent(identity.user(), scopes);
        for (ConsentScope scope : response.granted()) {
            auditLogger.logAccessGranted(identity.user(), scope);
        }
        for (ConsentScope scope : response.denied()) {
            auditLogger.logAccessDenied(identity.user(), scope, response.reasons().get(scope.fieldName()));
        }
        return maskingStrategy.mask(rawData, response.denied());
    }


}