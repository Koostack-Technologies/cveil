package com.koostack.operio.plugin.platform.keycloak;

import com.koostack.operio.core.exception.OperioServiceException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.OrganizationResource;
import org.keycloak.admin.client.resource.OrganizationsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keycloak Organizations API service — manages organizations as tenants within a single realm.
 *
 * <p>Each Operio tenant maps to a Keycloak Organization. The organization name is the tenant
 * identifier. Users are global (in the single realm) and scoped to tenants via organization
 * membership.
 */
public class KeycloakOrganizationService {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakOrganizationService.class);

    private final Keycloak adminClient;
    private final String realmName;

    public KeycloakOrganizationService(Keycloak adminClient, String realmName) {
        this.adminClient = adminClient;
        this.realmName = realmName;
    }

    private RealmResource realm() {
        return adminClient.realm(realmName);
    }

    private OrganizationsResource orgs() {
        return realm().organizations();
    }

    /**
     * Create a new organization (tenant).
     *
     * @param orgName unique organization name (used as tenant ID)
     * @param displayName human-readable display name
     * @return the created organization's Keycloak ID
     */
    public String createOrganization(String orgName, String displayName) {
        OrganizationRepresentation org = new OrganizationRepresentation();
        org.setName(orgName);
        org.setAlias(displayName != null ? displayName : orgName);
        org.setEnabled(true);
        org.setDescription("Operio tenant: " + orgName);

        Response response = orgs().create(org);
        try {
            if (response.getStatus() == 201) {
                String location = response.getHeaderString("Location");
                String orgId =
                        location != null && location.contains("/")
                                ? location.substring(location.lastIndexOf('/') + 1)
                                : orgName;
                LOG.info("Organization created: name={}, id={}", orgName, orgId);
                return orgId;
            } else if (response.getStatus() == 409) {
                // Already exists — look it up
                List<OrganizationRepresentation> existing = orgs().search(orgName, true, 0, 1);
                if (!existing.isEmpty()) {
                    LOG.info("Organization already exists: name={}", orgName);
                    return existing.get(0).getId();
                }
                throw new OperioServiceException(
                        "Organization '" + orgName + "' conflicts but cannot be found");
            } else {
                throw new OperioServiceException(
                        "Failed to create organization '"
                                + orgName
                                + "': HTTP "
                                + response.getStatus());
            }
        } finally {
            response.close();
        }
    }

    /** Get an organization by its Keycloak ID. */
    public OrganizationRepresentation getOrganization(String orgId) {
        try {
            return orgs().get(orgId).toRepresentation();
        } catch (jakarta.ws.rs.NotFoundException e) {
            throw new OperioServiceException("Organization not found: " + orgId);
        }
    }

    /** Find an organization by name. Returns null if not found. */
    public OrganizationRepresentation findByName(String orgName) {
        List<OrganizationRepresentation> results = orgs().search(orgName, true, 0, 1);
        return results.isEmpty() ? null : results.get(0);
    }

    /** List all organizations. */
    public List<OrganizationRepresentation> listOrganizations(int offset, int limit) {
        return orgs().list(offset, limit);
    }

    /**
     * Add a user as a member of an organization.
     *
     * @param orgId the Keycloak organization ID
     * @param userId the Keycloak user ID
     */
    public void addMember(String orgId, String userId) {
        OrganizationResource org = orgs().get(orgId);
        Response response = org.members().addMember(userId);
        try {
            if (response.getStatus() == 204 || response.getStatus() == 201) {
                LOG.info("User {} added to organization {}", userId, orgId);
            } else if (response.getStatus() == 409) {
                LOG.debug("User {} already a member of organization {}", userId, orgId);
            } else {
                throw new OperioServiceException(
                        "Failed to add user "
                                + userId
                                + " to organization "
                                + orgId
                                + ": HTTP "
                                + response.getStatus());
            }
        } finally {
            response.close();
        }
    }

    /**
     * Remove a user from an organization.
     *
     * @param orgId the Keycloak organization ID
     * @param userId the Keycloak user ID
     */
    public void removeMember(String orgId, String userId) {
        OrganizationResource org = orgs().get(orgId);
        Response response = org.members().removeMember(userId);
        try {
            if (response.getStatus() == 204) {
                LOG.info("User {} removed from organization {}", userId, orgId);
            } else {
                LOG.warn(
                        "Remove member returned HTTP {} for user {} in org {}",
                        response.getStatus(),
                        userId,
                        orgId);
            }
        } finally {
            response.close();
        }
    }

    /** List members of an organization. */
    public List<MemberRepresentation> listMembers(String orgId, int offset, int limit) {
        return orgs().get(orgId).members().list(offset, limit);
    }

    /**
     * Delete an organization.
     *
     * @param orgId the Keycloak organization ID
     */
    public void deleteOrganization(String orgId) {
        try {
            Response response = orgs().get(orgId).delete();
            try {
                LOG.info("Organization deleted: id={}, status={}", orgId, response.getStatus());
            } finally {
                response.close();
            }
        } catch (jakarta.ws.rs.NotFoundException e) {
            LOG.info("Organization {} does not exist — nothing to delete", orgId);
        }
    }

    /** Enable the organizations feature on the realm if not already enabled. */
    public void ensureOrganizationsEnabled() {
        var realmRep = realm().toRepresentation();
        if (realmRep.isOrganizationsEnabled() == null || !realmRep.isOrganizationsEnabled()) {
            realmRep.setOrganizationsEnabled(true);
            realm().update(realmRep);
            LOG.info("Organizations feature enabled on realm '{}'", realmName);
        }
    }
}
