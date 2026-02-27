package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.data.consent.ConsentAdminCommandService;
import com.koostack.operio.core.data.consent.ConsentAdminQueryService;
import com.koostack.operio.core.data.consent.ExpirationSweeperService;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExpirationSweeperService} implementation that finds expired consent
 * policies via the query service and revokes them via the command service.
 *
 * <p>Each revocation produces a {@code POLICY_REVOKED} event that flows
 * to the decision projection, deleting the Redis entry.
 */
public class JooqExpirationSweeperService implements ExpirationSweeperService {

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqExpirationSweeperService.class
    );

    private final ConsentAdminQueryService<?> queryService;
    private final ConsentAdminCommandService<?> commandService;
    private final Supplier<List<String>> tenantIdSupplier;

    /**
     * @param queryService     the query service to find expired policies
     * @param commandService   the command service to revoke expired policies
     * @param tenantIdSupplier supplies all tenant IDs for {@link #sweepAll()}
     */
    public JooqExpirationSweeperService(
        ConsentAdminQueryService<?> queryService,
        ConsentAdminCommandService<?> commandService,
        Supplier<List<String>> tenantIdSupplier
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.tenantIdSupplier = tenantIdSupplier;
    }

    @Override
    public int sweep() {
        Instant now = Instant.now();
        List<PolicySummaryEntry> expired = queryService.findExpiredPolicies(now);
        if (expired.isEmpty()) {
            return 0;
        }

        LOG.info("Found {} expired consent policies to revoke", expired.size());
        int revoked = 0;
        for (PolicySummaryEntry policy : expired) {
            try {
                ConsentScope scope = buildScope(policy);
                commandService.revokeConsent(policy.subject(), scope);
                revoked++;
            } catch (Exception e) {
                LOG.warn("Failed to revoke expired policy for subject={}, purpose={}",
                    policy.subject(), policy.purposeName(), e);
            }
        }
        LOG.info("Revoked {} of {} expired consent policies", revoked, expired.size());
        return revoked;
    }

    @Override
    public int sweepAll() {
        List<String> tenantIds = tenantIdSupplier.get();
        int total = 0;
        for (String tenantId : tenantIds) {
            try {
                int count = TenantContext.callWhere(tenantId, this::sweep);
                total += count;
            } catch (Exception e) {
                LOG.warn("Sweep failed for tenant {}", tenantId, e);
            }
        }
        return total;
    }

    static ConsentScope buildScope(PolicySummaryEntry policy) {
        if (policy.purposeName() == null) {
            LOG.warn("Expired policy for subject={} has null purpose; "
                + "falling back to wildcard purpose", policy.subject());
        }
        String purpose = policy.purposeName() != null
            ? policy.purposeName() : ConsentScope.WILDCARD;

        boolean hasCategory = policy.categoryName() != null
            && !ConsentScope.WILDCARD.equals(policy.categoryName());
        boolean hasField = policy.fieldName() != null
            && !ConsentScope.WILDCARD.equals(policy.fieldName());

        // Field scope takes precedence when both dimensions are present
        if (hasField) {
            String entity = policy.entityName() != null
                ? policy.entityName() : ConsentScope.WILDCARD;
            return ConsentScope.forField(policy.fieldName(), entity, purpose);
        }
        if (hasCategory) {
            return ConsentScope.forCategory(policy.categoryName(), purpose);
        }
        // Fallback: neither field nor category — use category-scoped with wildcard
        // to revoke at the broadest scope. This shouldn't happen with well-formed data.
        LOG.warn("Expired policy for subject={} has neither field nor category; "
            + "falling back to wildcard scope", policy.subject());
        return ConsentScope.forCategory(ConsentScope.WILDCARD, purpose);
    }
}
