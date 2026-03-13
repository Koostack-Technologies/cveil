package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.SubjectConsentView;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Query actuator that finds expired consent grants for sweeper processing. */
public class FindExpiredGrantsQueryActuator
        implements QueryActuator<
                FindExpiredGrantsQueryActuator.FindExpiredGrantsQuery,
                List<SubjectConsentView.ConsentLedgerEntryView>> {

    private final ConsentLedgerRepository consentLedgerRepository;
    private final ConsentAdminRecordMapper mapper;

    public FindExpiredGrantsQueryActuator(
            ConsentLedgerRepository consentLedgerRepository, ConsentAdminRecordMapper mapper) {
        this.consentLedgerRepository = consentLedgerRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for finding expired grants.
     *
     * @param cutoff entries with expires_at at or before this time are considered expired
     */
    public record FindExpiredGrantsQuery(Instant cutoff) {}

    @Override
    public List<SubjectConsentView.ConsentLedgerEntryView> execute(FindExpiredGrantsQuery query) {
        var records =
                consentLedgerRepository.findExpiredGrants(
                        java.time.LocalDateTime.ofInstant(query.cutoff(), ZoneOffset.UTC));
        return records.stream()
                .map(
                        r ->
                                mapper.toLedgerEntryView(
                                        r,
                                        r.get(
                                                com.koostack.cveil.plugin.consent.jooq.generated
                                                        .Tables.CONSENT_LEDGER
                                                        .SUBJECT_ID)))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
