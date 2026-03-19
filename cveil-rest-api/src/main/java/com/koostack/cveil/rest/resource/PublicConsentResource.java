package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.cqrs.QueryDispatcher;
import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.models.PurposeView;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.FindPolicyQueryActuator;
import com.koostack.cveil.plugin.consent.jooq.GrantConsentCommandActuator.GrantConsentCommand;
import com.koostack.cveil.plugin.consent.jooq.RevokeConsentCommandActuator.RevokeConsentCommand;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.dto.ConsentScopeDto;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public (unauthenticated) consent endpoints used by the embeddable consent banner widget.
 *
 * <p>Resolves tenant context from the {@code siteId} parameter, which maps to a {@code
 * banner_config} row. No API key or JWT required — designed for browser-side consent collection.
 */
@Path("/api/v1/public/consent")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Public Consent", description = "Embeddable consent banner endpoints (unauthenticated)")
public class PublicConsentResource {

    private static final Logger LOG = LoggerFactory.getLogger(PublicConsentResource.class);

    @Inject
    @Named("consentDispatcher")
    CommandDispatcher consentDispatcher;

    @Inject
    @Named("consentQueryDispatcher")
    QueryDispatcher consentQueryDispatcher;

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    // -- Public DTOs --------------------------------------------------------

    public record PublicGrantRequest(
            @NotBlank String siteId,
            @NotBlank String subject,
            @NotNull @Valid ConsentScopeDto scope,
            Instant expiresAt) {}

    public record PublicRevokeRequest(
            @NotBlank String siteId,
            @NotBlank String subject,
            @NotNull @Valid ConsentScopeDto scope) {}

    public record BannerConfigResponse(
            String siteId,
            String bannerPosition,
            Map<String, Object> bannerTheme,
            Map<String, Object> customText,
            List<String> purposes,
            boolean enabled) {}

    // -- Endpoints ----------------------------------------------------------

    @GET
    @Path("/config/{siteId}")
    @Operation(
            operationId = "getPublicBannerConfig",
            summary = "Get banner configuration for a site")
    public Response getBannerConfig(@PathParam("siteId") String siteId) {
        try {
            var config = lookupBannerConfigCached(siteId);
            if (config == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(
                                ErrorResponse.of(
                                        "BANNER_NOT_FOUND",
                                        "No banner configuration found for this site"))
                        .build();
            }
            if (!config.enabled) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(
                                ErrorResponse.of(
                                        "BANNER_DISABLED", "Banner is disabled for this site"))
                        .build();
            }
            return Response.ok(
                            new BannerConfigResponse(
                                    config.siteId,
                                    config.bannerPosition,
                                    parseJsonMap(config.bannerTheme),
                                    parseJsonMap(config.customText),
                                    parsePurposesList(config.purposes),
                                    config.enabled))
                    .header("Access-Control-Allow-Origin", config.siteDomain)
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to load banner config for siteId={}", siteId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to load banner configuration"))
                    .build();
        }
    }

    @POST
    @Path("/grant")
    @Operation(operationId = "publicConsentGrant", summary = "Grant consent via banner widget")
    public Response grant(
            @Valid PublicGrantRequest request,
            @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        try {
            var config = lookupBannerConfigCached(request.siteId());
            if (config == null || !config.enabled) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse.of("BANNER_NOT_FOUND", "Invalid or disabled site"))
                        .build();
            }

            if (!isOriginAllowed(headers, config.siteDomain)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(
                                ErrorResponse.of(
                                        "ORIGIN_DENIED",
                                        "Request origin does not match configured site domain"))
                        .build();
            }

            ConsentScope scope = request.scope().toModel();
            return TenantContext.callWhere(
                    config.tenantId,
                    () -> {
                        var policy = resolvePolicy(scope);
                        consentDispatcher.dispatch(
                                new GrantConsentCommand(
                                        request.subject(),
                                        policy.policyId(),
                                        scope.purposeName(),
                                        policy.policyType(),
                                        policy.field() != null ? policy.field().fieldName() : null,
                                        policy.field() != null ? policy.field().entityName() : null,
                                        policy.category() != null
                                                ? policy.category().categoryName()
                                                : null,
                                        "banner:" + request.siteId(),
                                        null,
                                        null,
                                        request.expiresAt()));
                        return Response.ok(MessageResponse.of("Consent granted"))
                                .header("Access-Control-Allow-Origin", config.siteDomain)
                                .build();
                    });
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Public consent grant failed for siteId={}", request.siteId(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to grant consent"))
                    .build();
        }
    }

    @POST
    @Path("/revoke")
    @Operation(operationId = "publicConsentRevoke", summary = "Revoke consent via banner widget")
    public Response revoke(
            @Valid PublicRevokeRequest request,
            @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        try {
            var config = lookupBannerConfigCached(request.siteId());
            if (config == null || !config.enabled) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse.of("BANNER_NOT_FOUND", "Invalid or disabled site"))
                        .build();
            }

            if (!isOriginAllowed(headers, config.siteDomain)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(
                                ErrorResponse.of(
                                        "ORIGIN_DENIED",
                                        "Request origin does not match configured site domain"))
                        .build();
            }

            ConsentScope scope = request.scope().toModel();
            return TenantContext.callWhere(
                    config.tenantId,
                    () -> {
                        var policy = resolvePolicy(scope);
                        consentDispatcher.dispatch(
                                new RevokeConsentCommand(
                                        request.subject(),
                                        policy.policyId(),
                                        scope.purposeName(),
                                        policy.policyType(),
                                        policy.field() != null ? policy.field().fieldName() : null,
                                        policy.field() != null ? policy.field().entityName() : null,
                                        policy.category() != null
                                                ? policy.category().categoryName()
                                                : null,
                                        "banner:" + request.siteId(),
                                        null,
                                        null));
                        return Response.ok(MessageResponse.of("Consent revoked"))
                                .header("Access-Control-Allow-Origin", config.siteDomain)
                                .build();
                    });
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.validation(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Public consent revoke failed for siteId={}", request.siteId(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to revoke consent"))
                    .build();
        }
    }

    // -- Banner config cache (60s TTL) ----------------------------------------

    private static final long CACHE_TTL_MS = 60_000;
    private static final ConcurrentHashMap<String, CachedConfig> CONFIG_CACHE =
            new ConcurrentHashMap<>();

    private record CachedConfig(BannerConfigRow row, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /** Evict a siteId from the cache (called by BannerConfigResource on update/delete). */
    public static void evictConfigCache(String siteId) {
        CONFIG_CACHE.remove(siteId);
    }

    private BannerConfigRow lookupBannerConfigCached(String siteId) throws Exception {
        var cached = CONFIG_CACHE.get(siteId);
        if (cached != null && !cached.isExpired()) {
            return cached.row();
        }
        var row = lookupBannerConfig(siteId);
        if (row != null) {
            CONFIG_CACHE.put(
                    siteId, new CachedConfig(row, System.currentTimeMillis() + CACHE_TTL_MS));
        } else {
            CONFIG_CACHE.remove(siteId);
        }
        return row;
    }

    // -- Origin validation --------------------------------------------------

    private static boolean isOriginAllowed(
            jakarta.ws.rs.core.HttpHeaders headers, String siteDomain) {
        if (siteDomain == null || siteDomain.isBlank()) return true;
        String origin = headers.getHeaderString("Origin");
        if (origin == null || origin.isBlank()) {
            // Fall back to Referer for non-CORS requests (e.g. same-origin)
            String referer = headers.getHeaderString("Referer");
            if (referer == null || referer.isBlank()) return true;
            try {
                origin = new URI(referer).getHost();
                return domainMatches(origin, siteDomain);
            } catch (Exception e) {
                return false;
            }
        }
        try {
            String originHost = new URI(origin).getHost();
            return domainMatches(originHost, siteDomain);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean domainMatches(String requestHost, String siteDomain) {
        if (requestHost == null) return false;
        // Extract host from siteDomain (may be "https://example.com" or just "example.com")
        String configHost = siteDomain;
        try {
            if (siteDomain.contains("://")) {
                configHost = new URI(siteDomain).getHost();
            }
        } catch (Exception e) {
            // Use as-is
        }
        if (configHost == null) return false;
        return requestHost.equalsIgnoreCase(configHost)
                || requestHost.endsWith("." + configHost.toLowerCase());
    }

    // -- Internal helpers ---------------------------------------------------

    private record BannerConfigRow(
            String siteId,
            String tenantId,
            String siteDomain,
            String bannerPosition,
            String bannerTheme,
            String customText,
            String purposes,
            boolean enabled) {}

    private BannerConfigRow lookupBannerConfig(String siteId) throws Exception {
        if (!dataSourceInstance.isResolvable()) return null;
        DataSource ds = dataSourceInstance.get();

        try (var conn = ds.getConnection();
                var ps =
                        conn.prepareStatement(
                                "SELECT site_id, tenant_id, site_domain, banner_position,"
                                        + " banner_theme, custom_text, purposes, enabled"
                                        + " FROM cveilconsent.banner_config WHERE site_id = ?")) {
            ps.setString(1, siteId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BannerConfigRow(
                        rs.getString("site_id"),
                        rs.getString("tenant_id"),
                        rs.getString("site_domain"),
                        rs.getString("banner_position"),
                        rs.getString("banner_theme"),
                        rs.getString("custom_text"),
                        rs.getString("purposes"),
                        rs.getBoolean("enabled"));
            }
        }
    }

    private PurposeView.PolicyView resolvePolicy(ConsentScope scope) {
        String policyType = isWildcardOrBlank(scope.fieldName()) ? "CATEGORY" : "FIELD";
        PurposeView.PolicyView policy =
                consentQueryDispatcher.dispatch(
                        new FindPolicyQueryActuator.FindPolicyQuery(
                                scope.purposeName(),
                                policyType,
                                isWildcardOrBlank(scope.fieldName()) ? null : scope.fieldName(),
                                isWildcardOrBlank(scope.entityName()) ? null : scope.entityName(),
                                isWildcardOrBlank(scope.category()) ? null : scope.category()));
        if (policy == null) {
            throw new IllegalArgumentException(
                    "No matching policy found for scope: "
                            + scope
                            + " — policies must be configured before consent can be collected");
        }
        return policy;
    }

    private static boolean isWildcardOrBlank(String s) {
        return s == null || s.isBlank() || "*".equals(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parsePurposesList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
