package com.koostack.operio.core.tenant.consent;

/**
 * Event type constants for consent admin domain events.
 *
 * <p>Each constant corresponds to a mutation published to the platform event
 * store by the command service. Projections consume these event types to
 * materialize the consent read model.
 */
public final class ConsentEventTypes {

    private ConsentEventTypes() {}

    // -- ConsentPolicyAggregate -----------------------------------------------
    public static final String POLICY_GRANTED = "consent.policy.granted";
    public static final String POLICY_REVOKED = "consent.policy.revoked";
    public static final String POLICY_ALL_REVOKED = "consent.policy.all-revoked";
    public static final String POLICY_AUDIT_DELETED = "consent.policy.audit-deleted";

    // -- EntityAggregate ------------------------------------------------------
    public static final String ENTITY_FIELD_REGISTERED = "consent.entity.field-registered";
    public static final String ENTITY_FIELD_CATEGORY_LINKED = "consent.entity.field-category-linked";
    public static final String ENTITY_DELETED = "consent.entity.deleted";
    public static final String ENTITY_FIELD_REMOVED = "consent.entity.field-removed";
    public static final String ENTITY_FIELD_CATEGORY_UNLINKED = "consent.entity.field-category-unlinked";

    // -- CategoryAggregate ----------------------------------------------------
    public static final String CATEGORY_REGISTERED = "consent.category.registered";
    public static final String CATEGORY_DELETED = "consent.category.deleted";

    // -- PurposeAggregate -----------------------------------------------------
    public static final String PURPOSE_REGISTERED = "consent.purpose.registered";
    public static final String PURPOSE_REGULATION_LINKED = "consent.purpose.regulation-linked";
    public static final String PURPOSE_DELETED = "consent.purpose.deleted";
    public static final String PURPOSE_REGULATION_UNLINKED = "consent.purpose.regulation-unlinked";

    // -- RegulationAggregate --------------------------------------------------
    public static final String REGULATION_REGISTERED = "consent.regulation.registered";
    public static final String REGULATION_DELETED = "consent.regulation.deleted";
}
