package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.tenant.consent.ExpirationSweeperService;
import com.koostack.operio.core.models.SubjectConsentView.ConsentLedgerEntryView;
import com.koostack.operio.core.models.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExpirationSweeperService} implementation that finds expired consent
 * grants via the query service and publishes {@code ConsentExpired} events
 * via the command service.
 *
 * <p>Each expiration produces a {@code CONSENT_EXPIRED} event that flows
 * to the admin and decision projections.
 */
public class JooqExpirationSweeperService implements ExpirationSweeperService {

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqExpirationSweeperService.class
    );

    private final ConsentAdminQueryService<?> queryService;
    private final ConsentAdminCommandService<?> commandService;
    private final Supplier<List<String>> tenantIdSupplier;

    /**
     * @param queryService     the query service to find expired grants
     * @param commandService   the command service to expire grants
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
        List<ConsentLedgerEntryView> expired = queryService.findExpiredGrants(now);
        if (expired.isEmpty()) {
            return 0;
        }

        LOG.info("Found {} expired consent grants to expire", expired.size());
        int expiredCount = 0;
        for (ConsentLedgerEntryView entry : expired) {
            try {
                commandService.expireConsentCommand(
                    entry.subjectId(),
                    entry.policyId(),
                    entry.purposeName(),
                    entry.policyType(),
                    entry.fieldName(),
                    entry.entityName(),
                    entry.categoryName()
                );
                expiredCount++;
            } catch (Exception e) {
                LOG.warn("Failed to expire grant for subject={}, policyId={}, purpose={}",
                    entry.subjectId(), entry.policyId(), entry.purposeName(), e);
            }
        }
        LOG.info("Expired {} of {} consent grants", expiredCount, expired.size());
        return expiredCount;
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
}
