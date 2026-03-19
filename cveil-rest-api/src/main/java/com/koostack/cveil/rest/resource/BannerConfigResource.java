package com.koostack.cveil.rest.resource;

import com.koostack.cveil.rest.auth.Permissions;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import com.koostack.cveil.rest.dto.CommonDtos.MessageResponse;
import com.koostack.cveil.rest.interceptor.TenantScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticated banner configuration management endpoints.
 *
 * <p>Allows tenants to create, update, and manage their embeddable consent banner configurations.
 * Each configuration generates a unique site ID used by the public consent API.
 */
@Path("/api/v1/banner")
@TenantScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_CONSENT)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class BannerConfigResource {

    private static final Logger LOG = LoggerFactory.getLogger(BannerConfigResource.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    @Named("platformDataSource")
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    @Inject CveilRequestHolder requestHolder;

    // -- DTOs ---------------------------------------------------------------

    public record CreateBannerConfigRequest(
            @NotBlank @Size(max = 512) String siteDomain,
            String bannerPosition,
            String bannerTheme,
            String customText,
            @NotNull List<String> purposes,
            String cookieCategories) {}

    public record UpdateBannerConfigRequest(
            String siteDomain,
            String bannerPosition,
            String bannerTheme,
            String customText,
            List<String> purposes,
            String cookieCategories,
            Boolean enabled) {}

    public record BannerConfigView(
            UUID id,
            String siteId,
            String tenantId,
            String siteDomain,
            String bannerPosition,
            String bannerTheme,
            String customText,
            String purposes,
            String cookieCategories,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {}

    // -- Endpoints ----------------------------------------------------------

    @GET
    @Path("/configs")
    @Operation(operationId = "listBannerConfigs", summary = "List banner configurations")
    public Response listConfigs(
            @jakarta.ws.rs.QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit,
            @jakarta.ws.rs.QueryParam("offset") @jakarta.ws.rs.DefaultValue("0") int offset) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);
        String tenantId = currentTenantId();
        limit = Math.max(1, Math.min(limit, 200));
        offset = Math.max(0, offset);

        try {
            DataSource ds = dataSourceInstance.get();
            List<BannerConfigView> configs = new ArrayList<>();

            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "SELECT banner_config_uuid, site_id, tenant_id, site_domain,"
                                        + " banner_position, banner_theme, custom_text, purposes,"
                                        + " cookie_categories, enabled, created_at, updated_at FROM"
                                        + " cveilconsent.banner_config WHERE tenant_id = ? ORDER BY"
                                        + " created_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, tenantId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        configs.add(
                                new BannerConfigView(
                                        UUID.fromString(rs.getString("banner_config_uuid")),
                                        rs.getString("site_id"),
                                        rs.getString("tenant_id"),
                                        rs.getString("site_domain"),
                                        rs.getString("banner_position"),
                                        rs.getString("banner_theme"),
                                        rs.getString("custom_text"),
                                        rs.getString("purposes"),
                                        rs.getString("cookie_categories"),
                                        rs.getBoolean("enabled"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant()));
                    }
                }
            }
            return Response.ok(configs).build();
        } catch (Exception e) {
            LOG.error("Failed to list banner configs for tenant={}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to list banner configurations"))
                    .build();
        }
    }

    @POST
    @Path("/configs")
    @Operation(operationId = "createBannerConfig", summary = "Create a new banner configuration")
    public Response createConfig(@Valid CreateBannerConfigRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        String tenantId = currentTenantId();
        String siteId = generateSiteId();

        try {
            DataSource ds = dataSourceInstance.get();
            String purposesJson =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(request.purposes());

            // Resolve purpose names → UUIDs for referential integrity
            String purposeUuidsJson = resolvePurposeUuids(ds, tenantId, request.purposes());

            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "INSERT INTO cveilconsent.banner_config (banner_config_uuid,"
                                        + " tenant_id, site_id, site_domain, banner_position,"
                                        + " banner_theme, custom_text, purposes, purposes_uuids,"
                                        + " cookie_categories, enabled) VALUES (?, ?, ?, ?, ?, ?,"
                                        + " ?, ?, ?, ?, true)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, tenantId);
                ps.setString(3, siteId);
                ps.setString(4, request.siteDomain());
                ps.setString(
                        5, request.bannerPosition() != null ? request.bannerPosition() : "bottom");
                ps.setString(6, request.bannerTheme() != null ? request.bannerTheme() : "{}");
                ps.setString(7, request.customText() != null ? request.customText() : "{}");
                ps.setString(8, purposesJson);
                ps.setString(9, purposeUuidsJson);
                ps.setString(
                        10, request.cookieCategories() != null ? request.cookieCategories() : "[]");
                ps.executeUpdate();
            }

            LOG.info("Banner config created for tenant={}, siteId={}", tenantId, siteId);
            return Response.status(Response.Status.CREATED)
                    .entity(
                            MessageResponse.of(
                                    "Banner configuration created with siteId: " + siteId))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to create banner config for tenant={}", tenantId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to create banner configuration"))
                    .build();
        }
    }

    @PUT
    @Path("/configs/{siteId}")
    @Operation(operationId = "updateBannerConfig", summary = "Update a banner configuration")
    public Response updateConfig(
            @PathParam("siteId") String siteId, @Valid UpdateBannerConfigRequest request) {
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        String tenantId = currentTenantId();

        try {
            DataSource ds = dataSourceInstance.get();
            List<String> setClauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (request.siteDomain() != null) {
                setClauses.add("site_domain = ?");
                params.add(request.siteDomain());
            }
            if (request.bannerPosition() != null) {
                setClauses.add("banner_position = ?");
                params.add(request.bannerPosition());
            }
            if (request.bannerTheme() != null) {
                setClauses.add("banner_theme = ?");
                params.add(request.bannerTheme());
            }
            if (request.customText() != null) {
                setClauses.add("custom_text = ?");
                params.add(request.customText());
            }
            if (request.purposes() != null) {
                setClauses.add("purposes = ?");
                params.add(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(request.purposes()));
            }
            if (request.cookieCategories() != null) {
                setClauses.add("cookie_categories = ?");
                params.add(request.cookieCategories());
            }
            if (request.enabled() != null) {
                setClauses.add("enabled = ?");
                params.add(request.enabled());
            }

            if (setClauses.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ErrorResponse.validation("No fields to update"))
                        .build();
            }

            setClauses.add("updated_at = CURRENT_TIMESTAMP");
            String sql =
                    "UPDATE cveilconsent.banner_config SET "
                            + String.join(", ", setClauses)
                            + " WHERE site_id = ? AND tenant_id = ?";
            params.add(siteId);
            params.add(tenantId);

            try (var conn = ds.getConnection();
                    var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(ErrorResponse.of("NOT_FOUND", "Banner config not found"))
                            .build();
                }
            }
            PublicConsentResource.evictConfigCache(siteId);
            return Response.ok(MessageResponse.of("Banner configuration updated")).build();
        } catch (Exception e) {
            LOG.error("Failed to update banner config siteId={}", siteId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to update banner configuration"))
                    .build();
        }
    }

    @DELETE
    @Path("/configs/{siteId}")
    @Operation(operationId = "deleteBannerConfig", summary = "Delete a banner configuration")
    public Response deleteConfig(@PathParam("siteId") String siteId) {
        Permissions.require(requestHolder.authorization(), Permissions.GOVERNANCE_WRITE);
        String tenantId = currentTenantId();

        try {
            DataSource ds = dataSourceInstance.get();
            try (var conn = ds.getConnection();
                    var ps =
                            conn.prepareStatement(
                                    "DELETE FROM cveilconsent.banner_config WHERE site_id = ? AND"
                                            + " tenant_id = ?")) {
                ps.setString(1, siteId);
                ps.setString(2, tenantId);
                int deleted = ps.executeUpdate();
                if (deleted == 0) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(ErrorResponse.of("NOT_FOUND", "Banner config not found"))
                            .build();
                }
            }
            PublicConsentResource.evictConfigCache(siteId);
            return Response.ok(MessageResponse.of("Banner configuration deleted")).build();
        } catch (Exception e) {
            LOG.error("Failed to delete banner config siteId={}", siteId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.internal("Failed to delete banner configuration"))
                    .build();
        }
    }

    @GET
    @Path("/configs/{siteId}/snippet")
    @Operation(
            operationId = "getBannerSnippet",
            summary = "Get embed snippet for a banner configuration")
    public Response getSnippet(@PathParam("siteId") String siteId) {
        Permissions.require(requestHolder.authorization(), Permissions.CONSENT_READ);

        String escapedSiteId =
                siteId.replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        String snippet =
                "<script src=\"https://cdn.cveil.com/banner.min.js\" data-site-id=\""
                        + escapedSiteId
                        + "\"></script>";
        return Response.ok(MessageResponse.of(snippet)).build();
    }

    // -- Helpers ------------------------------------------------------------

    private String currentTenantId() {
        return requestHolder.identity() != null ? requestHolder.identity().tenantId() : null;
    }

    private static String generateSiteId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "cvs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Resolve purpose names to UUIDs for referential integrity. Returns a JSON array of UUID
     * strings. Purposes that don't exist in the DB are silently skipped (validated at consent
     * time).
     */
    private static String resolvePurposeUuids(
            DataSource ds, String tenantId, List<String> purposeNames) throws Exception {
        if (purposeNames == null || purposeNames.isEmpty()) return "[]";
        StringBuilder sql =
                new StringBuilder(
                        "SELECT purpose_uuid FROM cveilconsent.purpose WHERE tenant_id = ? AND"
                                + " purpose_name IN (");
        for (int i = 0; i < purposeNames.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(')');

        List<String> uuids = new ArrayList<>();
        try (var conn = ds.getConnection();
                var ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, tenantId);
            for (int i = 0; i < purposeNames.size(); i++) {
                ps.setString(i + 2, purposeNames.get(i));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(rs.getString("purpose_uuid"));
                }
            }
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(uuids);
    }
}
