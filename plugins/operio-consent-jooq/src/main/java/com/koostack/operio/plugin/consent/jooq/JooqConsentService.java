import com.koostack.operio.core.service.ConsentService;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Assumes jOOQ Code Generation has run. 
// Replace 'com.koostack.operio.consent.jooq.generated.tables' with your actual package
import static com.koostack.operio.consent.jooq.generated.Tables.OPERIO_CONSENT;

public class JooqConsentService implements ConsentService {

    // Global context required because ServiceLoader demands a no-args constructor
    private static DSLContext dslContext;

    /**
     * Configuration hook. Call this during your application startup.
     * Example: JooqConsentService.init(dsl);
     */
    public static void init(DSLContext dsl) {
        dslContext = dsl;
    }

    private DSLContext getDsl() {
        if (dslContext == null) {
            throw new IllegalStateException("JooqConsentService not initialized. Call JooqConsentService.init(dsl) first.");
        }
        return dslContext;
    }

    @Override
    public String getName() {
        return "jooq";
    }

    @Override
    public int getPriority() {
        return 100; // High priority implementation
    }

    @Override
    public ConsentResponse evaluateConsent(String subject, Set<ConsentScope> accessRequests) {
        Set<ConsentScope> granted = new HashSet<>();
        Set<ConsentScope> denied = new HashSet<>();
        Map<String, String> reasons = new HashMap<>();

        // 1. Fetch all existing consents for this subject matching the requested scopes
        // optimization: In a real scenario, you might query specific fields OR load all user consents
        var existingConsents = getDsl().selectFrom(OPERIO_CONSENT)
                .where(OPERIO_CONSENT.SUBJECT_ID.eq(subject))
                .fetchMap(
                    r -> new ConsentScope(r.getFieldName(), r.getPurposeName()), 
                    r -> r.getStatus()
                );

        // 2. Evaluate
        for (ConsentScope request : accessRequests) {
            String status = existingConsents.get(request);

            if ("GRANTED".equals(status)) {
                granted.add(request);
            } else {
                denied.add(request);
                String reason = (status == null) ? "No consent record found" : "Consent status is " + status;
                reasons.put(request.fieldName(), reason);
            }
        }

        return ConsentResponse.to(subject, granted, denied, reasons);
    }

    @Override
    public void grantConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, "GRANTED");
    }

    @Override
    public void revokeConsent(String subject, ConsentScope accessRequest) {
        upsert(subject, accessRequest, "REVOKED");
    }

    private void upsert(String subject, ConsentScope scope, String status) {
        var now = OffsetDateTime.now();
        
        getDsl().insertInto(OPERIO_CONSENT)
                .set(OPERIO_CONSENT.ID, UUID.randomUUID().toString())
                .set(OPERIO_CONSENT.SUBJECT_ID, subject)
                .set(OPERIO_CONSENT.FIELD_NAME, scope.fieldName())
                .set(OPERIO_CONSENT.PURPOSE_NAME, scope.purposeName())
                .set(OPERIO_CONSENT.STATUS, status)
                .set(OPERIO_CONSENT.CREATED_AT, now)
                .set(OPERIO_CONSENT.UPDATED_AT, now)
                .onDuplicateKeyUpdate()
                .set(OPERIO_CONSENT.STATUS, status)
                .set(OPERIO_CONSENT.UPDATED_AT, now)
                .execute();
    }
}