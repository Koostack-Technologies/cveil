package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.tables.SubjectDsar.SUBJECT_DSAR;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * jOOQ-backed repository for the {@code subject_dsar} table.
 *
 * <p>Provides low-level CRUD operations for per-subject DSAR encryption
 * keys.  Used by the service layer ({@code JooqSubjectDsarService}) to
 * implement the digital shredder pattern.
 */
public class SubjectDsarRepository {

    private final DSLContext dsl;

    public SubjectDsarRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<String> findKeyByDsarId(UUID dsarId) {
        return dsl.select(SUBJECT_DSAR.DSAR_KEY)
                .from(SUBJECT_DSAR)
                .where(SUBJECT_DSAR.DSAR_ID.eq(dsarId))
                .fetchOptional(SUBJECT_DSAR.DSAR_KEY);
    }

    public void saveKey(UUID dsarId, String encryptedKey) {
        dsl.insertInto(SUBJECT_DSAR)
                .set(SUBJECT_DSAR.DSAR_ID, dsarId)
                .set(SUBJECT_DSAR.DSAR_KEY, encryptedKey)
                .set(SUBJECT_DSAR.CREATED_AT, LocalDateTime.now(ZoneOffset.UTC))
                .onConflict(SUBJECT_DSAR.DSAR_ID)
                .doUpdate()
                .set(SUBJECT_DSAR.DSAR_KEY, encryptedKey)
                .execute();
    }

    public void deleteKey(UUID dsarId) {
        dsl.deleteFrom(SUBJECT_DSAR)
                .where(SUBJECT_DSAR.DSAR_ID.eq(dsarId))
                .execute();
    }

    /**
     * Returns {@code true} if every event row carrying the given
     * {@code dsar_id} has been published by the outbox poller.
     *
     * <p>Uses raw jOOQ DSL to reference the {@code event} table — it
     * lives in the same tenant schema but is managed by
     * {@code operio-tenant-event-jooq}, so we avoid importing its
     * generated table class.
     *
     * <p><b>Column dependency:</b> relies on {@code event.dsar_id} (UUID)
     * and {@code event.published} (BOOLEAN) from {@code V9__digital_shredder.sql}.
     * If those columns are renamed, this query will fail at runtime.
     */
    public boolean allEventsPublished(UUID dsarId) {
        int unpublished = dsl.fetchCount(
            dsl.selectFrom(DSL.table("event"))
               .where(DSL.field("dsar_id", UUID.class).eq(dsarId))
               .and(DSL.field("published", Boolean.class).eq(false))
        );
        return unpublished == 0;
    }
}
