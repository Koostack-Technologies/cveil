package com.koostack.cveil.core;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.core.models.BatchConsentEntry;
import com.koostack.cveil.core.models.BatchConsentResult;
import com.koostack.cveil.core.models.BatchConsentResult.BatchItemError;
import com.koostack.cveil.core.models.ConsentScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BatchConsentResult}, {@link BatchConsentEntry}, and
 * {@link BatchItemError} — the batch consent operation DTOs.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Construction and field accessors for all three records</li>
 *   <li>Succeeded/failed counts reflect actual results</li>
 *   <li>Error collection captures index, scope, and message</li>
 *   <li>Empty batch (zero items) edge case</li>
 *   <li>All-success and all-failure scenarios</li>
 *   <li>Record semantics (equals, hashCode, toString)</li>
 * </ul>
 */
@DisplayName("Batch consent DTOs")
class BatchConsentTest {

    private static final ConsentScope SCOPE_EMAIL = ConsentScope.to(
        "pii",
        "email",
        "marketing"
    );
    private static final ConsentScope SCOPE_NAME = ConsentScope.to(
        "pii",
        "full_name",
        "marketing"
    );
    private static final ConsentScope SCOPE_PHONE = ConsentScope.to(
        "contact",
        "phone",
        "marketing"
    );
    private static final ConsentScope SCOPE_WITH_PURPOSE = ConsentScope.to(
        "pii",
        "address",
        "user",
        "marketing"
    );
    private static final Instant FUTURE = Instant.now().plus(
        30,
        ChronoUnit.DAYS
    );

    // -----------------------------------------------------------------------
    // BatchConsentEntry
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentEntry")
    class BatchConsentEntryTests {

        @Test
        @DisplayName("construction with scope and expiresAt")
        void constructionWithExpiration() {
            BatchConsentEntry entry = new BatchConsentEntry(
                SCOPE_EMAIL,
                FUTURE
            );

            assertEquals(SCOPE_EMAIL, entry.scope());
            assertEquals(FUTURE, entry.expiresAt());
        }

        @Test
        @DisplayName("construction with null expiresAt (no expiration)")
        void constructionWithoutExpiration() {
            BatchConsentEntry entry = new BatchConsentEntry(SCOPE_NAME, null);

            assertEquals(SCOPE_NAME, entry.scope());
            assertNull(entry.expiresAt());
        }

        @Test
        @DisplayName("equals compares scope and expiresAt")
        void equalsComparesAllFields() {
            BatchConsentEntry a = new BatchConsentEntry(SCOPE_EMAIL, FUTURE);
            BatchConsentEntry b = new BatchConsentEntry(SCOPE_EMAIL, FUTURE);
            BatchConsentEntry c = new BatchConsentEntry(SCOPE_NAME, FUTURE);
            BatchConsentEntry d = new BatchConsentEntry(SCOPE_EMAIL, null);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c, "Different scope should not be equal");
            assertNotEquals(a, d, "Different expiresAt should not be equal");
        }

        @Test
        @DisplayName("toString includes scope information")
        void toStringIncludesScope() {
            BatchConsentEntry entry = new BatchConsentEntry(SCOPE_EMAIL, null);
            String s = entry.toString();
            assertTrue(
                s.contains("email"),
                "toString should contain field name from scope: " + s
            );
        }
    }

    // -----------------------------------------------------------------------
    // BatchItemError
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchItemError")
    class BatchItemErrorTests {

        @Test
        @DisplayName("construction with index, scope, and message")
        void construction() {
            BatchItemError error = new BatchItemError(
                2,
                SCOPE_PHONE,
                "Consent already revoked"
            );

            assertEquals(2, error.index());
            assertEquals(SCOPE_PHONE, error.scope());
            assertEquals("Consent already revoked", error.message());
        }

        @Test
        @DisplayName("zero index is valid (first item in batch)")
        void zeroIndex() {
            BatchItemError error = new BatchItemError(0, SCOPE_EMAIL, "fail");
            assertEquals(0, error.index());
        }

        @Test
        @DisplayName("null message is allowed")
        void nullMessage() {
            BatchItemError error = new BatchItemError(1, SCOPE_EMAIL, null);
            assertNull(error.message());
        }

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            BatchItemError a = new BatchItemError(0, SCOPE_EMAIL, "fail");
            BatchItemError b = new BatchItemError(0, SCOPE_EMAIL, "fail");
            BatchItemError c = new BatchItemError(1, SCOPE_EMAIL, "fail");
            BatchItemError d = new BatchItemError(0, SCOPE_NAME, "fail");
            BatchItemError e = new BatchItemError(0, SCOPE_EMAIL, "different");

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c, "Different index should not be equal");
            assertNotEquals(a, d, "Different scope should not be equal");
            assertNotEquals(a, e, "Different message should not be equal");
        }

        @Test
        @DisplayName("toString includes index and message")
        void toStringIncludesFields() {
            BatchItemError error = new BatchItemError(
                3,
                SCOPE_PHONE,
                "timeout"
            );
            String s = error.toString();
            assertTrue(s.contains("3"), "toString should contain index");
            assertTrue(
                s.contains("timeout"),
                "toString should contain message"
            );
        }
    }

    // -----------------------------------------------------------------------
    // BatchConsentResult — all success
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentResult — all success")
    class AllSuccess {

        @Test
        @DisplayName("all items succeed → succeeded=N, failed=0, errors empty")
        void allItemsSucceed() {
            BatchConsentResult result = new BatchConsentResult(3, 0, List.of());

            assertEquals(3, result.succeeded());
            assertEquals(0, result.failed());
            assertNotNull(result.errors());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("single item succeeds")
        void singleSuccess() {
            BatchConsentResult result = new BatchConsentResult(1, 0, List.of());

            assertEquals(1, result.succeeded());
            assertEquals(0, result.failed());
            assertTrue(result.errors().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // BatchConsentResult — all failure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentResult — all failure")
    class AllFailure {

        @Test
        @DisplayName(
            "all items fail → succeeded=0, failed=N, errors has N entries"
        )
        void allItemsFail() {
            List<BatchItemError> errors = List.of(
                new BatchItemError(0, SCOPE_EMAIL, "DB connection lost"),
                new BatchItemError(1, SCOPE_NAME, "DB connection lost"),
                new BatchItemError(2, SCOPE_PHONE, "DB connection lost")
            );

            BatchConsentResult result = new BatchConsentResult(0, 3, errors);

            assertEquals(0, result.succeeded());
            assertEquals(3, result.failed());
            assertEquals(3, result.errors().size());
        }

        @Test
        @DisplayName("error entries preserve original index and scope")
        void errorEntriesPreserveDetails() {
            List<BatchItemError> errors = List.of(
                new BatchItemError(0, SCOPE_EMAIL, "fail-0"),
                new BatchItemError(1, SCOPE_NAME, "fail-1")
            );

            BatchConsentResult result = new BatchConsentResult(0, 2, errors);

            assertEquals(0, result.errors().get(0).index());
            assertEquals(SCOPE_EMAIL, result.errors().get(0).scope());
            assertEquals("fail-0", result.errors().get(0).message());

            assertEquals(1, result.errors().get(1).index());
            assertEquals(SCOPE_NAME, result.errors().get(1).scope());
            assertEquals("fail-1", result.errors().get(1).message());
        }
    }

    // -----------------------------------------------------------------------
    // BatchConsentResult — partial success
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentResult — partial success")
    class PartialSuccess {

        @Test
        @DisplayName("partial success: succeeded + failed = total")
        void partialSuccessCounts() {
            List<BatchItemError> errors = List.of(
                new BatchItemError(1, SCOPE_NAME, "duplicate")
            );

            BatchConsentResult result = new BatchConsentResult(2, 1, errors);

            assertEquals(2, result.succeeded());
            assertEquals(1, result.failed());
            assertEquals(1, result.errors().size());
            assertEquals(
                3,
                result.succeeded() + result.failed(),
                "succeeded + failed should equal total batch size"
            );
        }

        @Test
        @DisplayName(
            "errors list only contains failed items, not succeeded ones"
        )
        void errorsOnlyContainFailures() {
            // Batch of 5: items 0, 2, 4 succeed; items 1, 3 fail
            List<BatchItemError> errors = List.of(
                new BatchItemError(1, SCOPE_NAME, "scope not registered"),
                new BatchItemError(3, SCOPE_PHONE, "rate limit exceeded")
            );

            BatchConsentResult result = new BatchConsentResult(3, 2, errors);

            assertEquals(3, result.succeeded());
            assertEquals(2, result.failed());
            assertEquals(2, result.errors().size());

            // Verify failed indices
            assertEquals(1, result.errors().get(0).index());
            assertEquals(3, result.errors().get(1).index());
        }
    }

    // -----------------------------------------------------------------------
    // BatchConsentResult — edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentResult — edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty batch → succeeded=0, failed=0, errors empty")
        void emptyBatch() {
            BatchConsentResult result = new BatchConsentResult(0, 0, List.of());

            assertEquals(0, result.succeeded());
            assertEquals(0, result.failed());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("large batch counts are preserved")
        void largeBatch() {
            BatchConsentResult result = new BatchConsentResult(
                9999,
                1,
                List.of(new BatchItemError(5000, SCOPE_EMAIL, "timeout"))
            );

            assertEquals(9999, result.succeeded());
            assertEquals(1, result.failed());
            assertEquals(10000, result.succeeded() + result.failed());
        }
    }

    // -----------------------------------------------------------------------
    // BatchConsentResult — record semantics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentResult — record semantics")
    class RecordSemantics {

        @Test
        @DisplayName("equals compares all fields")
        void equalsComparesAllFields() {
            List<BatchItemError> errors = List.of(
                new BatchItemError(0, SCOPE_EMAIL, "fail")
            );

            BatchConsentResult a = new BatchConsentResult(2, 1, errors);
            BatchConsentResult b = new BatchConsentResult(2, 1, errors);
            BatchConsentResult c = new BatchConsentResult(3, 0, List.of());

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("not equal when error lists differ")
        void notEqualWhenErrorsDiffer() {
            BatchConsentResult a = new BatchConsentResult(
                1,
                1,
                List.of(new BatchItemError(0, SCOPE_EMAIL, "fail-a"))
            );
            BatchConsentResult b = new BatchConsentResult(
                1,
                1,
                List.of(new BatchItemError(0, SCOPE_EMAIL, "fail-b"))
            );

            assertNotEquals(
                a,
                b,
                "Different error messages should produce unequal results"
            );
        }

        @Test
        @DisplayName("toString includes counts")
        void toStringIncludesCounts() {
            BatchConsentResult result = new BatchConsentResult(
                5,
                2,
                List.of(
                    new BatchItemError(1, SCOPE_EMAIL, "err1"),
                    new BatchItemError(3, SCOPE_NAME, "err2")
                )
            );

            String s = result.toString();
            assertTrue(
                s.contains("5"),
                "toString should contain succeeded count"
            );
            assertTrue(s.contains("2"), "toString should contain failed count");
        }
    }

    // -----------------------------------------------------------------------
    // ConsentScope with purpose (used in batch entries)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BatchConsentEntry with purpose-scoped ConsentScope")
    class WithPurpose {

        @Test
        @DisplayName(
            "batch entry can carry a purpose-scoped ConsentScope with entityName"
        )
        void entryWithPurposeScope() {
            BatchConsentEntry entry = new BatchConsentEntry(
                SCOPE_WITH_PURPOSE,
                FUTURE
            );

            assertEquals("pii", entry.scope().category());
            assertEquals("address", entry.scope().fieldName());
            assertEquals("user", entry.scope().entityName());
            assertEquals("marketing", entry.scope().purposeName());
            assertEquals(FUTURE, entry.expiresAt());
        }

        @Test
        @DisplayName("error references purpose-scoped scope correctly")
        void errorWithPurposeScope() {
            BatchItemError error = new BatchItemError(
                0,
                SCOPE_WITH_PURPOSE,
                "invalid purpose"
            );

            assertEquals(SCOPE_WITH_PURPOSE, error.scope());
            assertEquals("marketing", error.scope().purposeName());
        }
    }
}
