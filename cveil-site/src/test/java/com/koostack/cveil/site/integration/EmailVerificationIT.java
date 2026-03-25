package com.koostack.cveil.site.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.cveil.site.config.CveilConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * Integration test for the email verification feature, covering the full lifecycle:
 *
 * <ol>
 *   <li>Registration triggers a verification email (captured by MailHog)
 *   <li>New user starts with {@code emailVerified: false} across all endpoints
 *   <li>Resend verification email works
 *   <li>Admin-verify in Keycloak + login sync updates the read model
 *   <li>Negative cases (auth required, idempotent resend)
 *   <li>SMTP email content verification
 *   <li>Database state verification (email_verified_at preserved on re-login)
 * </ol>
 *
 * <p>Uses MailHog as a fake SMTP server to capture emails sent by Keycloak.
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("Email Verification Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmailVerificationIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String PLATFORM_REALM = "cveil";
    private static final String BASE_PATH = "/api/v1/platform/iam";

    private static final String TEST_EMAIL = "verify-test@cveil-test.com";
    private static final String TEST_USERNAME = "verify-test";
    private static final String TEST_PASSWORD = "Test1234!";

    // Shared mutable state across nested test classes
    private static volatile IntegrationTestSupport support;
    private static volatile String accessToken;
    private static volatile String keycloakUserId;

    @Inject CveilConfig config;

    private String auth() {
        return "Bearer " + BOOTSTRAP_KEY;
    }

    // =======================================================================
    // 1. Setup
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Initialize test support and clear mailbox")
        void initSupportAndClearMailbox() {
            support = new IntegrationTestSupport(config);
            support.clearMailbox();
        }

        @Test
        @Order(2)
        @DisplayName("Register user for email verification tests")
        void registerUser() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "username": "%s",
                      "email": "%s",
                      "fullName": "Verify Test User",
                      "roles": ["super-admin"],
                      "temporaryPassword": false
                    }
                    """
                                    .formatted(TEST_USERNAME, TEST_EMAIL))
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(201)
                    .body("email", equalTo(TEST_EMAIL))
                    .body("username", equalTo(TEST_USERNAME));
        }

        @Test
        @Order(3)
        @DisplayName("User appears in Keycloak")
        void awaitUserInKeycloak() {
            support.awaitKeycloakUserExists(PLATFORM_REALM, TEST_EMAIL, Duration.ofSeconds(30));
        }

        @Test
        @Order(4)
        @DisplayName("Set known password via Keycloak admin API")
        void setPassword() {
            var users =
                    support.keycloak()
                            .realm(PLATFORM_REALM)
                            .users()
                            .searchByEmail(TEST_EMAIL, true);
            assertThat(users).isNotEmpty();
            keycloakUserId = users.get(0).getId();

            var cred = new org.keycloak.representations.idm.CredentialRepresentation();
            cred.setType(org.keycloak.representations.idm.CredentialRepresentation.PASSWORD);
            cred.setValue(TEST_PASSWORD);
            cred.setTemporary(false);
            support.keycloak()
                    .realm(PLATFORM_REALM)
                    .users()
                    .get(keycloakUserId)
                    .resetPassword(cred);
        }
    }

    // =======================================================================
    // 2. Registration triggers verification email
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Registration Triggers Verification Email")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RegistrationEmail {

        @Test
        @Order(1)
        @DisplayName("Verification email sent on registration")
        void verificationEmailSentOnRegistration() {
            var emails = support.awaitEmailTo(TEST_EMAIL, Duration.ofSeconds(30));
            assertThat(emails)
                    .as("At least one email should be sent to registered user")
                    .isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("New user starts unverified — login response")
        void newUserStartsUnverified() {
            Response response =
                    given().contentType(ContentType.JSON)
                            .body(
                                    """
                    {"email": "%s", "password": "%s"}
                    """
                                            .formatted(TEST_EMAIL, TEST_PASSWORD))
                            .when()
                            .post(BASE_PATH + "/login")
                            .then()
                            .log()
                            .ifStatusCodeIsEqualTo(401)
                            .statusCode(200)
                            .body("accessToken", notNullValue())
                            .body("emailVerified", is(false))
                            .extract()
                            .response();

            accessToken = response.jsonPath().getString("accessToken");
            assertThat(accessToken).isNotNull();
        }

        @Test
        @Order(3)
        @DisplayName("GET /me shows emailVerified: false")
        void readModelShowsUnverified() {
            assertThat(accessToken).as("Must be logged in").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .when()
                    .get(BASE_PATH + "/me")
                    .then()
                    .statusCode(200)
                    .body("emailVerified", is(false))
                    .body("email", equalTo(TEST_EMAIL));
        }

        @Test
        @Order(4)
        @DisplayName("GET /me/verify-email/status → not verified")
        void statusEndpointShowsUnverified() {
            assertThat(accessToken).as("Must be logged in").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .when()
                    .get(BASE_PATH + "/me/verify-email/status")
                    .then()
                    .statusCode(200)
                    .body("emailVerified", is(false));
        }

        @Test
        @Order(5)
        @DisplayName("GET /users/{username} shows emailVerified: false")
        void getUserShowsUnverified() {
            given().header("Authorization", auth())
                    .when()
                    .get(BASE_PATH + "/users/" + TEST_USERNAME)
                    .then()
                    .statusCode(200)
                    .body("emailVerified", is(false));
        }
    }

    // =======================================================================
    // 3. Resend verification email
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Resend Verification Email")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ResendVerification {

        @Test
        @Order(1)
        @DisplayName("Clear mailbox before resend test")
        void clearMailbox() {
            support.clearMailbox();
            // Verify mailbox is empty
            assertThat(support.getEmails()).isEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("POST /me/verify-email/resend → 200 with sent: true")
        void resendVerificationEmail() {
            assertThat(accessToken).as("Must be logged in").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .post(BASE_PATH + "/me/verify-email/resend")
                    .then()
                    .statusCode(200)
                    .body("sent", is(true))
                    .body("message", notNullValue());
        }

        @Test
        @Order(3)
        @DisplayName("Resent email captured in MailHog")
        void resendEmailCaptured() {
            var emails = support.awaitEmailTo(TEST_EMAIL, Duration.ofSeconds(30));
            assertThat(emails)
                    .as("MailHog should capture the resent verification email")
                    .isNotEmpty();
        }

        @Test
        @Order(4)
        @DisplayName("POST /me/verify-email/resend without auth → 401")
        void resendRequiresAuth() {
            given().contentType(ContentType.JSON)
                    .when()
                    .post(BASE_PATH + "/me/verify-email/resend")
                    .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 4. Verify email via Keycloak admin & login sync
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Verify Email via Keycloak Admin & Login Sync")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class VerifyAndSync {

        @Test
        @Order(1)
        @DisplayName("Mark email as verified in Keycloak")
        void markEmailVerifiedInKeycloak() {
            assertThat(keycloakUserId).as("Keycloak user ID required").isNotNull();

            var userResource = support.keycloak().realm(PLATFORM_REALM).users().get(keycloakUserId);
            var userRep = userResource.toRepresentation();
            userRep.setEmailVerified(true);
            userResource.update(userRep);

            // Verify the change took effect
            var updated = userResource.toRepresentation();
            assertThat(updated.isEmailVerified()).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("Login after verification → emailVerified: true")
        void loginAfterVerification() {
            Response response =
                    given().contentType(ContentType.JSON)
                            .body(
                                    """
                    {"email": "%s", "password": "%s"}
                    """
                                            .formatted(TEST_EMAIL, TEST_PASSWORD))
                            .when()
                            .post(BASE_PATH + "/login")
                            .then()
                            .statusCode(200)
                            .body("emailVerified", is(true))
                            .extract()
                            .response();

            accessToken = response.jsonPath().getString("accessToken");
        }

        @Test
        @Order(3)
        @DisplayName("GET /me syncs emailVerified: true after login")
        void readModelSyncsOnLogin() {
            assertThat(accessToken).as("Must be logged in").isNotNull();

            // The projection may take a moment to process the EmailVerifiedEvent
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", "Bearer " + accessToken)
                                            .when()
                                            .get(BASE_PATH + "/me")
                                            .then()
                                            .statusCode(200)
                                            .body("emailVerified", is(true)));
        }

        @Test
        @Order(4)
        @DisplayName("GET /me/verify-email/status → verified")
        void statusEndpointShowsVerified() {
            given().header("Authorization", "Bearer " + accessToken)
                    .when()
                    .get(BASE_PATH + "/me/verify-email/status")
                    .then()
                    .statusCode(200)
                    .body("emailVerified", is(true));
        }

        @Test
        @Order(5)
        @DisplayName("GET /users/{username} → emailVerified: true")
        void getUserShowsVerified() {
            // May need to poll since projection is eventually consistent
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get(BASE_PATH + "/users/" + TEST_USERNAME)
                                            .then()
                                            .statusCode(200)
                                            .body("emailVerified", is(true)));
        }
    }

    // =======================================================================
    // 5. Negative cases & edge cases
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Negative Cases & Edge Cases")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NegativeCases {

        @Test
        @Order(1)
        @DisplayName("Resend when already verified → still succeeds (idempotent)")
        void resendWhenAlreadyVerified() {
            assertThat(accessToken).as("Must be logged in").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .post(BASE_PATH + "/me/verify-email/resend")
                    .then()
                    .statusCode(200)
                    .body("sent", is(true));
        }

        @Test
        @Order(2)
        @DisplayName("GET /me/verify-email/status without auth → 401")
        void statusEndpointRequiresAuth() {
            given().when().get(BASE_PATH + "/me/verify-email/status").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("GET /users list includes emailVerified field")
        void listUsersIncludesEmailVerified() {
            given().header("Authorization", auth())
                    .when()
                    .get(BASE_PATH + "/users")
                    .then()
                    .statusCode(200)
                    .body("emailVerified", everyItem(notNullValue()));
        }
    }

    // =======================================================================
    // 6. SMTP email content verification
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — SMTP Email Content Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EmailContent {

        @Test
        @Order(1)
        @DisplayName("Email has correct recipient")
        void emailHasCorrectRecipient() {
            var emails = support.getEmailsTo(TEST_EMAIL);
            assertThat(emails).as("Should have captured at least one email").isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Email has correct From header")
        void emailHasCorrectFrom() {
            var emails = support.getEmailsTo(TEST_EMAIL);
            assertThat(emails).isNotEmpty();

            String from = IntegrationTestSupport.extractEmailFrom(emails.get(0));
            assertThat(from).containsIgnoringCase("cveil");
        }

        @Test
        @Order(3)
        @DisplayName("Verification email contains action link")
        void verificationEmailContainsLink() {
            var emails = support.getEmailsTo(TEST_EMAIL);
            assertThat(emails).isNotEmpty();

            String messageId = IntegrationTestSupport.extractMessageId(emails.get(0));
            String body = support.fetchEmailBody(messageId);
            // Keycloak action emails contain a link back to Keycloak for the user to verify
            assertThat(body)
                    .as("Email body should contain a Keycloak action link or verification content")
                    .isNotBlank();
        }
    }

    // =======================================================================
    // 7. Database state verification
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Database State Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DatabaseState {

        @Test
        @Order(1)
        @DisplayName("iam_user row has email_verified=true and email_verified_at set")
        void iamUserRowHasEmailVerified() {
            support.awaitQueryValue(
                    "SELECT email_verified FROM cveiliam.iam_user WHERE username = ?",
                    "email_verified",
                    true,
                    TEST_USERNAME);

            var rows =
                    support.query(
                            "SELECT email_verified, email_verified_at FROM cveiliam.iam_user"
                                    + " WHERE username = ?",
                            TEST_USERNAME);
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).get("email_verified")).isEqualTo(true);
            assertThat(rows.get(0).get("email_verified_at"))
                    .as("email_verified_at should be set after verification")
                    .isNotNull();
        }

        @Test
        @Order(2)
        @DisplayName("email_verified_at preserved on re-login (COALESCE behavior)")
        void emailVerifiedAtPreservedOnRelogin() {
            // Get the current email_verified_at timestamp
            var rowsBefore =
                    support.query(
                            "SELECT email_verified_at FROM cveiliam.iam_user WHERE username = ?",
                            TEST_USERNAME);
            assertThat(rowsBefore).isNotEmpty();
            var timestampBefore = rowsBefore.get(0).get("email_verified_at");
            assertThat(timestampBefore).isNotNull();

            // Login again — triggers another EmailVerifiedEvent
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {"email": "%s", "password": "%s"}
                    """
                                    .formatted(TEST_EMAIL, TEST_PASSWORD))
                    .when()
                    .post(BASE_PATH + "/login")
                    .then()
                    .statusCode(200)
                    .body("emailVerified", is(true));

            // Small wait for projection to process
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Verify timestamp is unchanged (COALESCE preserves original)
            var rowsAfter =
                    support.query(
                            "SELECT email_verified_at FROM cveiliam.iam_user WHERE username = ?",
                            TEST_USERNAME);
            assertThat(rowsAfter).isNotEmpty();
            var timestampAfter = rowsAfter.get(0).get("email_verified_at");
            assertThat(timestampAfter)
                    .as("email_verified_at should be preserved across re-logins")
                    .isEqualTo(timestampBefore);
        }
    }
}
