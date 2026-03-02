package com.koostack.operio.core.tenant.consent;

public final class ConsentEventTypes {

    private ConsentEventTypes() {}

    // -- FieldAggregate -------------------------------------------------------
    public static final String FIELD_REGISTERED = "consent.field.registered";
    public static final String FIELD_UPDATED = "consent.field.updated";
    public static final String FIELD_REMOVED = "consent.field.removed";
    public static final String FIELD_CATEGORY_ASSIGNED = "consent.field.category-assigned";
    public static final String FIELD_CATEGORY_UNASSIGNED = "consent.field.category-unassigned";

    // -- PurposeAggregate -----------------------------------------------------
    public static final String PURPOSE_REGISTERED = "consent.purpose.registered";
    public static final String PURPOSE_UPDATED = "consent.purpose.updated";
    public static final String PURPOSE_REMOVED = "consent.purpose.removed";
    public static final String PURPOSE_REGULATION_LINKED = "consent.purpose.regulation-linked";
    public static final String PURPOSE_REGULATION_UNLINKED = "consent.purpose.regulation-unlinked";
    public static final String PURPOSE_POLICY_ADDED = "consent.purpose.policy-added";
    public static final String PURPOSE_POLICY_REMOVED = "consent.purpose.policy-removed";

    // -- RegulationAggregate --------------------------------------------------
    public static final String REGULATION_REGISTERED = "consent.regulation.registered";
    public static final String REGULATION_UPDATED = "consent.regulation.updated";
    public static final String REGULATION_REMOVED = "consent.regulation.removed";
    public static final String REGULATION_JURISDICTION_ADDED = "consent.regulation.jurisdiction-added";
    public static final String REGULATION_JURISDICTION_REMOVED = "consent.regulation.jurisdiction-removed";

    // -- SubjectConsentAggregate ----------------------------------------------
    public static final String CONSENT_GRANTED = "consent.subject.granted";
    public static final String CONSENT_REVOKED = "consent.subject.revoked";
    public static final String CONSENT_EXPIRED = "consent.subject.expired";
    public static final String CONSENT_ALL_REVOKED = "consent.subject.all-revoked";

    // -- DsarAggregate --------------------------------------------------------
    public static final String SUBJECT_AUDIT_DELETED = "consent.dsar.audit-deleted";
}
