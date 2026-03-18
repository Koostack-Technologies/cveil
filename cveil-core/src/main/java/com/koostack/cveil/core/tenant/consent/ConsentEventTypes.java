package com.koostack.cveil.core.tenant.consent;

public final class ConsentEventTypes {

    private ConsentEventTypes() {}

    // -- EntityAggregate ------------------------------------------------------
    public static final String ENTITY_REGISTERED = "consent.entity.registered";
    public static final String ENTITY_UPDATED = "consent.entity.updated";
    public static final String ENTITY_REMOVED = "consent.entity.removed";

    // -- CategoryAggregate ----------------------------------------------------
    public static final String CATEGORY_REGISTERED = "consent.category.registered";
    public static final String CATEGORY_UPDATED = "consent.category.updated";
    public static final String CATEGORY_REMOVED = "consent.category.removed";

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
    public static final String REGULATION_JURISDICTION_ADDED =
            "consent.regulation.jurisdiction-added";
    public static final String REGULATION_JURISDICTION_REMOVED =
            "consent.regulation.jurisdiction-removed";

    // -- JurisdictionAggregate ------------------------------------------------
    public static final String JURISDICTION_REGISTERED = "consent.jurisdiction.registered";
    public static final String JURISDICTION_UPDATED = "consent.jurisdiction.updated";
    public static final String JURISDICTION_REMOVED = "consent.jurisdiction.removed";

    // -- SubjectConsentAggregate ----------------------------------------------
    public static final String CONSENT_GRANTED = "consent.subject.granted";
    public static final String CONSENT_REVOKED = "consent.subject.revoked";
    public static final String CONSENT_EXPIRED = "consent.subject.expired";
    public static final String CONSENT_ALL_REVOKED = "consent.subject.all-revoked";

    // -- SubjectAggregate (entity lifecycle) -----------------------------------
    public static final String SUBJECT_REGISTERED = "consent.subject-entity.registered";
    public static final String SUBJECT_UPDATED = "consent.subject-entity.updated";
    public static final String SUBJECT_REMOVED = "consent.subject-entity.removed";

    // -- DsarAggregate --------------------------------------------------------
    public static final String SUBJECT_AUDIT_DELETED = "consent.dsar.audit-deleted";
    public static final String SUBJECT_SHREDDED = "consent.dsar.shredded";
    public static final String SUBJECT_DATA_DELETED = "consent.dsar.data-deleted";
}
