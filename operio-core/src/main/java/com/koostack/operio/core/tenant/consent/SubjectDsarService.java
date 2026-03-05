package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.spi.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing per-subject DSAR encryption keys used by the
 * digital shredder pattern.
 *
 * <p>Keys are looked up by a deterministic {@code dsar_id} UUID derived
 * from the subject identifier via {@link #deriveDsarId(String)}.
 * Deleting a key permanently shreds all encrypted event payloads for
 * that subject (GDPR Article 17 — right to be forgotten).
 *
 * @param <T> the configuration type accepted by this plugin
 */
public interface SubjectDsarService<T> extends Plugin<T> {

    /**
     * Derive the deterministic {@code dsar_id} UUID for a given subject
     * identifier.  Uses a Type-3 (name-based MD5) UUID with the prefix
     * {@code "dsar:"} to avoid collisions with other UUID derivations in
     * the system.
     *
     * @param subjectIdentifier the subject name / subject id
     * @return deterministic UUID
     */
    static UUID deriveDsarId(String subjectIdentifier) {
        return UUID.nameUUIDFromBytes(
                ("dsar:" + subjectIdentifier).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Look up the encrypted DSAR key for the given {@code dsar_id}.
     *
     * @param dsarId UUID derived from the subject identifier
     * @return the encrypted key, or empty if the subject has been shredded
     *         (or was never registered)
     */
    Optional<String> findKeyByDsarId(UUID dsarId);

    /**
     * Persist a new DSAR key.  Called synchronously during subject
     * registration, <em>before</em> any events are published.
     *
     * @param dsarId       UUID derived from the subject identifier
     * @param encryptedKey the AES-256 key encrypted with the admin
     *                     (OPERIO_DB_ENCRYPTION_KEY) key
     */
    void saveKey(UUID dsarId, String encryptedKey);

    /**
     * Delete the DSAR key — the "shred" operation.  After this call every
     * event payload encrypted with this key is permanently unreadable.
     *
     * @param dsarId UUID derived from the subject identifier
     */
    void deleteKey(UUID dsarId);

    /**
     * Check whether all events associated with the given {@code dsar_id}
     * have been published by the outbox poller.
     *
     * <p>This is the deterministic safety check before shredding: if any
     * events are still {@code published = false}, deleting the DSAR key
     * would prevent the outbox from decrypting and delivering them to
     * downstream projections.
     *
     * @param dsarId UUID derived from the subject identifier
     * @return {@code true} if zero unpublished events remain for this
     *         {@code dsar_id}, {@code false} otherwise
     */
    boolean allEventsPublished(UUID dsarId);
}
