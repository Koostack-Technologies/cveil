package com.koostack.cveil.site.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.site.config.CveilConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("BannerConfigResource Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BannerConfigResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "banner_it_tenant";
    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;
    private static volatile String createdSiteId;

    @Inject CveilConfig config;

    // ─── Phase 1: Setup ─────────────────────────────────────────────────

    @Nested
    @Order(1)
    @DisplayName("1 — Setup & Auth Enforcement")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SetupAndAuth {

        @Test
        @Order(1)
        @DisplayName("Provision tenant and login")
        void provisionTenantAndLogin() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_ID, "Banner IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Register prerequisite purpose: marketing")
        void registerMarketingPurpose() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                            { "purposeName": "marketing", "description": "Marketing purposes" }
                            """)
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201)
                    .body("message", containsString("marketing"));
        }

        @Test
        @Order(3)
        @DisplayName("Unauthenticated request to banner configs → 401")
        void unauthenticatedReturns401() {
            given().when().get("/api/v1/banner/configs").then().statusCode(401);
        }

        @Test
        @Order(4)
        @DisplayName("Invalid token → 401")
        void invalidTokenReturns401() {
            given().header("Authorization", "Bearer invalid-token")
                    .when()
                    .get("/api/v1/banner/configs")
                    .then()
                    .statusCode(401);
        }
    }

    // ─── Phase 2: Create Banner Config ──────────────────────────────────

    @Nested
    @Order(2)
    @DisplayName("2 — Create Banner Config")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CreateBannerConfig {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("POST /api/v1/banner/configs → 201 with siteId")
        void createBannerConfig() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                                    {
                                      "siteDomain": "https://example.com",
                                      "bannerPosition": "bottom",
                                      "bannerTheme": "{}",
                                      "customText": "{}",
                                      "purposes": ["marketing"],
                                      "cookieCategories": "[]"
                                    }
                                    """)
                            .when()
                            .post("/api/v1/banner/configs")
                            .then()
                            .statusCode(201)
                            .body("message", containsString("siteId"))
                            .extract()
                            .response();

            String message = response.jsonPath().getString("message");
            assertThat(message).contains("siteId:");

            // Extract siteId from message: "Banner configuration created with siteId: cvs_xxxx"
            createdSiteId =
                    message.substring(message.indexOf("siteId:") + "siteId:".length()).trim();
            assertThat(createdSiteId).startsWith("cvs_");
        }
    }

    // ─── Phase 3: List Banner Configs ───────────────────────────────────

    @Nested
    @Order(3)
    @DisplayName("3 — List Banner Configs")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ListBannerConfigs {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("GET /api/v1/banner/configs returns list containing created config")
        void listBannerConfigs() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().header("Authorization", auth())
                    .queryParam("limit", 50)
                    .queryParam("offset", 0)
                    .when()
                    .get("/api/v1/banner/configs")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("siteId", hasItem(createdSiteId));
        }
    }

    // ─── Phase 4: Public Config Lookup ──────────────────────────────────

    @Nested
    @Order(4)
    @DisplayName("4 — Public Config Lookup (no auth)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PublicConfigLookup {

        @Test
        @Order(1)
        @DisplayName("GET /api/v1/public/consent/config/{siteId} → 200 with banner config")
        void publicConfigLookup() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/public/consent/config/" + createdSiteId)
                    .then()
                    .statusCode(200)
                    .body("siteId", equalTo(createdSiteId))
                    .body("bannerPosition", equalTo("bottom"))
                    .body("purposes", hasItem("marketing"))
                    .body("enabled", equalTo(true));
        }
    }

    // ─── Phase 5: Update Banner Config ──────────────────────────────────

    @Nested
    @Order(5)
    @DisplayName("5 — Update Banner Config")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UpdateBannerConfig {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("PUT /api/v1/banner/configs/{siteId} → 200 update bannerPosition to top")
        void updateBannerConfig() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                            { "bannerPosition": "top", "enabled": true }
                            """)
                    .when()
                    .put("/api/v1/banner/configs/" + createdSiteId)
                    .then()
                    .statusCode(200)
                    .body("message", notNullValue());
        }

        @Test
        @Order(2)
        @DisplayName("Public config reflects updated bannerPosition")
        void publicConfigReflectsUpdate() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/public/consent/config/" + createdSiteId)
                    .then()
                    .statusCode(200)
                    .body("bannerPosition", equalTo("top"));
        }
    }

    // ─── Phase 6: Get Snippet ───────────────────────────────────────────

    @Nested
    @Order(6)
    @DisplayName("6 — Get Embed Snippet")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GetSnippet {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("GET /api/v1/banner/configs/{siteId}/snippet → contains <script and siteId")
        void getSnippet() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            Response response =
                    given().header("Authorization", auth())
                            .when()
                            .get("/api/v1/banner/configs/" + createdSiteId + "/snippet")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            String body = response.asString();
            assertThat(body).contains("<script");
            assertThat(body).contains(createdSiteId);
        }
    }

    // ─── Phase 7: Delete Banner Config ──────────────────────────────────

    @Nested
    @Order(7)
    @DisplayName("7 — Delete Banner Config")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeleteBannerConfig {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("DELETE /api/v1/banner/configs/{siteId} → 200")
        void deleteBannerConfig() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/banner/configs/" + createdSiteId)
                    .then()
                    .statusCode(200)
                    .body("message", notNullValue());
        }

        @Test
        @Order(2)
        @DisplayName("Public config lookup after deletion → 404")
        void publicConfigAfterDeletion() {
            assertNotNull(createdSiteId, "createdSiteId required from Phase 2");

            given().contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/public/consent/config/" + createdSiteId)
                    .then()
                    .statusCode(404);
        }
    }

    // ─── Phase 8: Not Found Cases ───────────────────────────────────────

    @Nested
    @Order(8)
    @DisplayName("8 — Not Found Cases")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NotFoundCases {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("GET public config for non-existent siteId → 404")
        void publicConfigNonExistent() {
            given().contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/public/consent/config/cvs_nonexistent_site")
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(2)
        @DisplayName("DELETE non-existent siteId → 404")
        void deleteNonExistent() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/banner/configs/cvs_nonexistent_site")
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(3)
        @DisplayName("PUT non-existent siteId → 404")
        void updateNonExistent() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                            { "bannerPosition": "top" }
                            """)
                    .when()
                    .put("/api/v1/banner/configs/cvs_nonexistent_site")
                    .then()
                    .statusCode(404);
        }
    }
}
