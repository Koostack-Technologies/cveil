package com.koostack.operio.core.data.consent.events;

/**
 * Marker interface for all semantic domain event payloads.
 *
 * <p>Every event payload record published to the tenant event store
 * implements this interface. Projections deserialize the JSON payload
 * back into the concrete record using the {@code eventType} discriminator.
 */
public interface DomainEvent {}
