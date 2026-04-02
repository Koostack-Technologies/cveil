package com.koostack.cveil.rest.billing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BillableEndpoints")
class BillableEndpointsTest {

    @Nested
    @DisplayName("isBillable")
    class IsBillable {

        @Test
        @DisplayName("consent/subjects path is billable")
        void subjectsPath() {
            assertTrue(BillableEndpoints.isBillable("/api/v1/subjects"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/subjects/consent/grant"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/subjects/some-subject/summary"));
        }

        @Test
        @DisplayName("governance path is billable")
        void governancePath() {
            assertTrue(BillableEndpoints.isBillable("/api/v1/governance"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/governance/purposes"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/governance/regulations/gdpr"));
        }

        @Test
        @DisplayName("catalogue path is billable")
        void cataloguePath() {
            assertTrue(BillableEndpoints.isBillable("/api/v1/catalogue"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/catalogue/fields"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/catalogue/entities/user"));
        }

        @Test
        @DisplayName("graph path is billable")
        void graphPath() {
            assertTrue(BillableEndpoints.isBillable("/api/v1/consent"));
            assertTrue(BillableEndpoints.isBillable("/api/v1/consent/graph"));
        }

        @Test
        @DisplayName("graph/events path is billable (under /api/v1/consent)")
        void graphEventsPath() {
            assertTrue(BillableEndpoints.isBillable("/api/v1/consent/events"));
        }

        @Test
        @DisplayName("billing path is NOT billable")
        void billingPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/billing"));
            assertFalse(BillableEndpoints.isBillable("/api/v1/billing/subscription"));
        }

        @Test
        @DisplayName("IAM path is NOT billable")
        void iamPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/platform/iam/users"));
            assertFalse(BillableEndpoints.isBillable("/api/v1/platform/iam/me/tokens"));
        }

        @Test
        @DisplayName("metrics path is NOT billable")
        void metricsPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/metrics"));
            assertFalse(BillableEndpoints.isBillable("/api/v1/platform/metrics"));
        }

        @Test
        @DisplayName("platform path is NOT billable")
        void platformPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/platform/tenants"));
        }

        @Test
        @DisplayName("health path is NOT billable")
        void healthPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/health"));
        }

        @Test
        @DisplayName("old events path is NOT billable")
        void oldEventsPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/events"));
            assertFalse(BillableEndpoints.isBillable("/api/v1/events/invalidations"));
        }

        @Test
        @DisplayName("webhooks path is NOT billable")
        void webhooksPath() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/webhooks/stripe"));
        }

        @Test
        @DisplayName("null path is NOT billable")
        void nullPath() {
            assertFalse(BillableEndpoints.isBillable(null));
        }

        @Test
        @DisplayName("partial prefix mismatch is NOT billable")
        void partialPrefixMismatch() {
            assertFalse(BillableEndpoints.isBillable("/api/v1/subjectsExtra"));
            assertFalse(BillableEndpoints.isBillable("/api/v1/governanceFoo"));
        }
    }

    @Nested
    @DisplayName("resolveResource")
    class ResolveResource {

        @Test
        @DisplayName("subjects resolves to 'consent'")
        void subjectsResource() {
            assertEquals(
                    Optional.of("consent"),
                    BillableEndpoints.resolveResource("/api/v1/subjects/consent/grant"));
        }

        @Test
        @DisplayName("governance resolves to 'governance'")
        void governanceResource() {
            assertEquals(
                    Optional.of("governance"),
                    BillableEndpoints.resolveResource("/api/v1/governance/purposes"));
        }

        @Test
        @DisplayName("catalogue resolves to 'catalogue'")
        void catalogueResource() {
            assertEquals(
                    Optional.of("catalogue"),
                    BillableEndpoints.resolveResource("/api/v1/catalogue/fields"));
        }

        @Test
        @DisplayName("consent/graph resolves to 'graph'")
        void graphResource() {
            assertEquals(
                    Optional.of("graph"),
                    BillableEndpoints.resolveResource("/api/v1/consent/graph"));
        }

        @Test
        @DisplayName("consent/graph/events resolves to 'graph'")
        void graphEventsResource() {
            assertEquals(
                    Optional.of("graph"),
                    BillableEndpoints.resolveResource("/api/v1/consent/events"));
        }

        @Test
        @DisplayName("non-billable path returns empty")
        void nonBillable() {
            assertEquals(
                    Optional.empty(),
                    BillableEndpoints.resolveResource("/api/v1/billing/subscription"));
        }

        @Test
        @DisplayName("null path returns empty")
        void nullPath() {
            assertEquals(Optional.empty(), BillableEndpoints.resolveResource(null));
        }
    }

    @Nested
    @DisplayName("isPatAuth")
    class IsPatAuth {

        @Test
        @DisplayName("access-token provider is PAT")
        void accessTokenIsPat() {
            assertTrue(BillableEndpoints.isPatAuth("access-token"));
        }

        @Test
        @DisplayName("keycloak provider is not PAT")
        void keycloakIsNotPat() {
            assertFalse(BillableEndpoints.isPatAuth("keycloak"));
        }

        @Test
        @DisplayName("bootstrap-key provider is not PAT")
        void bootstrapIsNotPat() {
            assertFalse(BillableEndpoints.isPatAuth("bootstrap-key"));
        }

        @Test
        @DisplayName("null provider is not PAT")
        void nullIsNotPat() {
            assertFalse(BillableEndpoints.isPatAuth(null));
        }
    }

    @Nested
    @DisplayName("billablePathPrefixes")
    class BillablePathPrefixes {

        @Test
        @DisplayName("returns all 5 billable prefixes")
        void returnsAllPrefixes() {
            List<String> prefixes = BillableEndpoints.billablePathPrefixes();
            assertEquals(5, prefixes.size());
            assertTrue(prefixes.contains("/api/v1/subjects"));
            assertTrue(prefixes.contains("/api/v1/governance"));
            assertTrue(prefixes.contains("/api/v1/catalogue"));
            assertTrue(prefixes.contains("/api/v1/consent"));
            assertTrue(prefixes.contains("/api/v1/dsar"));
        }
    }
}
