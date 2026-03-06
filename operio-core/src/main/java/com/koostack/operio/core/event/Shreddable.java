package com.koostack.operio.core.event;

/**
 * Marker interface for domain events that contain subject PII and should be encrypted with the
 * subject's per-subject DSAR key.
 *
 * <p>When the {@link EventService} receives an event whose payload implements {@code Shreddable},
 * it encrypts the serialised JSON payload using the subject's DSAR key before persisting it. Later,
 * when the subject exercises their right to erasure (GDPR Article 17), deleting the DSAR key
 * renders all encrypted payloads permanently unreadable — the "digital shredder" pattern.
 */
public interface Shreddable {

    /**
     * Return the identifier of the data subject this event belongs to. The value is used to derive
     * the {@code dsar_id} UUID that links the event to its encryption key in the {@code
     * subject_dsar} table.
     *
     * @return subject identifier (e.g. subject name or subject id)
     */
    String subjectIdentifier();
}
