package com.koostack.operio.rest.config;

import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Application-level OpenAPI definition for the Operio REST API.
 *
 * <p>This class carries the {@link OpenAPIDefinition} annotation that SmallRye OpenAPI reads at
 * build time (or startup) to produce the {@code /q/openapi} specification document and the Swagger
 * UI at {@code /q/swagger-ui}.
 *
 * <h2>Security Scheme</h2>
 *
 * <p>All authenticated endpoints use Bearer token authentication via the {@code Authorization:
 * Bearer api-key} header. The API key is created through the platform admin endpoint and is
 * verified by {@link com.koostack.operio.rest.filter.ApiKeyAuthFilter}.
 *
 * <h2>Tags</h2>
 *
 * <p>Tags are declared here to control ordering in Swagger UI. Each resource class references its
 * tag by name.
 *
 * @see com.koostack.operio.rest.resource.ConsentResource
 * @see com.koostack.operio.rest.resource.CatalogueResource
 * @see com.koostack.operio.rest.resource.GovernanceResource
 * @see com.koostack.operio.rest.resource.PlatformResource
 * @see com.koostack.operio.rest.resource.HealthResource
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Operio Consent Management API",
                        version = "1.0.0",
                        description =
                                "Multi-tenant consent management REST API. Operio provides"
                                    + " fine-grained, field-level consent tracking with full audit"
                                    + " history, batch operations, and regulatory compliance"
                                    + " support (GDPR, CCPA, HIPAA). All authenticated endpoints"
                                    + " require a Bearer API key in the Authorization header.",
                        contact =
                                @Contact(
                                        name = "KooStack",
                                        url = "https://github.com/koostack/operio"),
                        license =
                                @License(
                                        name = "Apache 2.0",
                                        url = "https://www.apache.org/licenses/LICENSE-2.0")),
        tags = {
            @Tag(
                    name = OperioOpenApiConfig.TAG_CONSENT,
                    description =
                            "Consent operations: grant, revoke, evaluate, "
                                    + "batch, subject summary, and audit history. Requires "
                                    + "consent:read or consent:write roles."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_CATALOGUE,
                    description =
                            "Data catalogue: manage fields, categories, "
                                    + "and entities. Requires admin:read or admin:write roles."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_GOVERNANCE,
                    description =
                            "Governance framework: manage purposes, regulations, "
                                    + "jurisdictions, and their relationships. Requires "
                                    + "admin:read or admin:write roles."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_ADMIN,
                    description =
                            "Event streams: cache invalidation signals "
                                    + "via SSE. Requires admin:read role."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_PLATFORM,
                    description =
                            "Platform administration: tenant lifecycle and "
                                    + "API key management. Requires the super-admin role."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_IAM,
                    description =
                            "Identity and Access Management: user registration, "
                                    + "service accounts, authentication, and role management."),
            @Tag(
                    name = OperioOpenApiConfig.TAG_HEALTH,
                    description =
                            "Health and readiness checks. Public endpoint, "
                                    + "no authentication required."),
        },
        components =
                @Components(
                        securitySchemes =
                                @SecurityScheme(
                                        securitySchemeName = OperioOpenApiConfig.SECURITY_SCHEME,
                                        type = SecuritySchemeType.HTTP,
                                        scheme = "bearer",
                                        bearerFormat = "API Key",
                                        description =
                                                "Bearer API key issued via POST"
                                                    + " /api/v1/platform/api-keys. Include in the"
                                                    + " Authorization header as: Bearer"
                                                    + " your-api-key\n\n"
                                                    + "Roles are embedded in the key and determine"
                                                    + " which endpoints are accessible:\n"
                                                    + "- consent:read: read-only consent"
                                                    + " operations\n"
                                                    + "- consent:write: mutating consent"
                                                    + " operations\n"
                                                    + "- admin:read: read-only schema"
                                                    + " administration\n"
                                                    + "- admin:write: mutating schema"
                                                    + " administration\n"
                                                    + "- super-admin: full platform access (all"
                                                    + " endpoints)")))
public class OperioOpenApiConfig {

    // -- Tag name constants (referenced by resource classes) -----------------

    /** Tag for consent endpoints. */
    public static final String TAG_CONSENT = "Consent";

    /** Tag for admin/schema endpoints. */
    public static final String TAG_ADMIN = "Admin";

    /** Tag for data catalogue endpoints (fields, categories, entities). */
    public static final String TAG_CATALOGUE = "Catalogue";

    /** Tag for governance framework endpoints (purposes, regulations, jurisdictions). */
    public static final String TAG_GOVERNANCE = "Governance";

    /**
     * @deprecated Use {@link #TAG_GOVERNANCE} instead. Kept for backward compatibility.
     */
    @Deprecated public static final String TAG_LEGAL = TAG_GOVERNANCE;

    /** Tag for platform administration endpoints. */
    public static final String TAG_PLATFORM = "Platform";

    /** Tag for IAM endpoints (users, service accounts, authentication). */
    public static final String TAG_IAM = "IAM";

    /** Tag for health/readiness endpoints. */
    public static final String TAG_HEALTH = "Health";

    /** Name of the Bearer security scheme used by all authenticated endpoints. */
    public static final String SECURITY_SCHEME = "BearerAuth";

    private OperioOpenApiConfig() {
        // Utility class — no instances
    }
}
