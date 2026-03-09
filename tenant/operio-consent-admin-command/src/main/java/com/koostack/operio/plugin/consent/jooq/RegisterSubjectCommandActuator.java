package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectAggregate;
import com.koostack.operio.core.tenant.consent.events.SubjectRegisteredEvent;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.crypto.KeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterSubjectCommandActuator
        implements CommandActuator<RegisterSubjectCommandActuator.RegisterSubjectCommand, Void> {

    private final EventService<?> eventService;
    private final SubjectDsarService<?> dsarService;
    private final UnaryOperator<String> keyEncryptor;

    public RegisterSubjectCommandActuator(EventService<?> eventService) {
        this(eventService, null, null);
    }

    public RegisterSubjectCommandActuator(
            EventService<?> eventService,
            SubjectDsarService<?> dsarService,
            UnaryOperator<String> keyEncryptor) {
        this.eventService = eventService;
        this.dsarService = dsarService;
        this.keyEncryptor = keyEncryptor;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RegisterSubjectCommandActuator.class);

    public record RegisterSubjectCommand(String subjectName, String description) {}

    @Override
    public Void execute(RegisterSubjectCommand command) {
        Objects.requireNonNull(command.subjectName(), "subjectName must not be null");
        String tenantId = TenantContext.require();

        // Generate and persist DSAR key BEFORE publishing the event so that the
        // EventService can encrypt shreddable payloads immediately.
        if (dsarService != null) {
            String rawKey = generateDsarKey();
            String encryptedKey = keyEncryptor != null ? keyEncryptor.apply(rawKey) : rawKey;
            UUID dsarId = SubjectDsarService.deriveDsarId(command.subjectName());
            dsarService.saveKey(dsarId, encryptedKey);
            LOG.debug("DSAR key persisted for subject: {}", command.subjectName());
        }

        UUID aggregateId = SubjectAggregate.deriveId(command.subjectName());
        var payload = new SubjectRegisteredEvent(command.subjectName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.SUBJECT_REGISTERED, key, payload));
        LOG.info("Subject registered: {}", command.subjectName());
        return null;
    }

    private static String generateDsarKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            byte[] keyBytes = keyGen.generateKey().getEncoded();
            return Base64.getEncoder().encodeToString(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate AES-256 DSAR key", e);
        }
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
