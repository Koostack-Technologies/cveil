package com.koostack.cveil.core.tenant.consent.events;

import com.koostack.cveil.core.event.DomainEvent;

/**
 * Published when a subject's DSAR encryption key is permanently deleted ("shredded"). After this
 * event, all event payloads encrypted with the subject's key are permanently unreadable. GDPR
 * Article 17 — right to be forgotten.
 */
public record SubjectShreddedEvent(String subjectName) implements DomainEvent {}
