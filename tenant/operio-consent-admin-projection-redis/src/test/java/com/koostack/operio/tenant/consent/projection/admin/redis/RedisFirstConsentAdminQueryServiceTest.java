package com.koostack.operio.tenant.consent.projection.admin.redis;

import com.koostack.operio.core.models.FieldView;
import com.koostack.operio.core.models.PurposeView;
import com.koostack.operio.core.models.RegulationView;
import com.koostack.operio.core.models.SubjectConsentView;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisFirstConsentAdminQueryService")
class RedisFirstConsentAdminQueryServiceTest {

    private RedisConsentAdminQueryService redis;
    private ConsentAdminQueryService<?> jooq;
    private RedisFirstConsentAdminQueryService composite;

    @BeforeEach
    void setUp() {
        redis = mock(RedisConsentAdminQueryService.class);
        jooq = mock(ConsentAdminQueryService.class);
        composite = new RedisFirstConsentAdminQueryService(redis, jooq);
    }

    // ── Field ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getField")
    class GetField {

        @Test
        @DisplayName("returns Redis result on hit")
        void redisHit() {
            FieldView view = new FieldView(0L, "email", "desc",
                    new FieldView.EntityView(0L, "user"), List.of());
            when(redis.getField("email", "user")).thenReturn(view);

            assertSame(view, composite.getField("email", "user"));
            verify(jooq, never()).getField(anyString(), anyString());
        }

        @Test
        @DisplayName("falls back to JOOQ on Redis miss")
        void redisMiss() {
            when(redis.getField("email", "user")).thenReturn(null);
            FieldView jooqView = new FieldView(1L, "email", "desc",
                    new FieldView.EntityView(1L, "user"), List.of());
            when(jooq.getField("email", "user")).thenReturn(jooqView);

            assertSame(jooqView, composite.getField("email", "user"));
        }
    }

    @Nested
    @DisplayName("listFields")
    class ListFields {

        @Test
        @DisplayName("returns Redis result on hit")
        void redisHit() {
            List<FieldView> views = List.of(
                    new FieldView(0L, "email", null,
                            new FieldView.EntityView(0L, "user"), List.of()));
            when(redis.listFields()).thenReturn(views);

            assertSame(views, composite.listFields());
            verify(jooq, never()).listFields();
        }

        @Test
        @DisplayName("falls back to JOOQ on null (cache miss)")
        void redisMissNull() {
            when(redis.listFields()).thenReturn(null);
            List<FieldView> jooqViews = List.of();
            when(jooq.listFields()).thenReturn(jooqViews);

            assertSame(jooqViews, composite.listFields());
        }
    }

    // ── Purpose ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getPurpose")
    class GetPurpose {

        @Test
        @DisplayName("returns Redis result on hit")
        void redisHit() {
            PurposeView view = new PurposeView(0L, "marketing", "desc", List.of(), List.of());
            when(redis.getPurpose("marketing")).thenReturn(view);

            assertSame(view, composite.getPurpose("marketing"));
            verify(jooq, never()).getPurpose(anyString());
        }

        @Test
        @DisplayName("falls back to JOOQ on miss")
        void redisMiss() {
            when(redis.getPurpose("marketing")).thenReturn(null);
            PurposeView jooqView = new PurposeView(1L, "marketing", "desc", List.of(), List.of());
            when(jooq.getPurpose("marketing")).thenReturn(jooqView);

            assertSame(jooqView, composite.getPurpose("marketing"));
        }
    }

    // ── Regulation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getRegulation")
    class GetRegulation {

        @Test
        @DisplayName("returns Redis result on hit")
        void redisHit() {
            RegulationView view = new RegulationView(0L, "GDPR", "desc", List.of());
            when(redis.getRegulation("GDPR")).thenReturn(view);

            assertSame(view, composite.getRegulation("GDPR"));
            verify(jooq, never()).getRegulation(anyString());
        }

        @Test
        @DisplayName("falls back to JOOQ on miss")
        void redisMiss() {
            when(redis.getRegulation("GDPR")).thenReturn(null);
            RegulationView jooqView = new RegulationView(1L, "GDPR", "desc", List.of());
            when(jooq.getRegulation("GDPR")).thenReturn(jooqView);

            assertSame(jooqView, composite.getRegulation("GDPR"));
        }
    }

    // ── SubjectConsent ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSubjectConsent")
    class GetSubjectConsent {

        @Test
        @DisplayName("returns Redis result on hit")
        void redisHit() {
            SubjectConsentView view = new SubjectConsentView("sub-1", List.of());
            when(redis.getSubjectConsent("sub-1")).thenReturn(view);

            assertSame(view, composite.getSubjectConsent("sub-1"));
            verify(jooq, never()).getSubjectConsent(anyString());
        }

        @Test
        @DisplayName("falls back to JOOQ on miss")
        void redisMiss() {
            when(redis.getSubjectConsent("sub-1")).thenReturn(null);
            SubjectConsentView jooqView = new SubjectConsentView("sub-1", List.of());
            when(jooq.getSubjectConsent("sub-1")).thenReturn(jooqView);

            assertSame(jooqView, composite.getSubjectConsent("sub-1"));
        }
    }

    // ── countSubjectConsentEntries ──────────────────────────────────────────

    @Nested
    @DisplayName("countSubjectConsentEntries")
    class CountEntries {

        @Test
        @DisplayName("returns Redis count when non-negative")
        void redisHit() {
            when(redis.countSubjectConsentEntries("sub-1")).thenReturn(3);
            assertEquals(3, composite.countSubjectConsentEntries("sub-1"));
            verify(jooq, never()).countSubjectConsentEntries(anyString());
        }

        @Test
        @DisplayName("falls back to JOOQ when Redis returns -1")
        void redisMiss() {
            when(redis.countSubjectConsentEntries("sub-1")).thenReturn(-1);
            when(jooq.countSubjectConsentEntries("sub-1")).thenReturn(5);
            assertEquals(5, composite.countSubjectConsentEntries("sub-1"));
        }
    }

    // ── Always-JOOQ delegations ────────────────────────────────────────────

    @Test
    @DisplayName("findExpiredGrants always delegates to JOOQ")
    void findExpiredGrantsDelegatesToJooq() {
        Instant cutoff = Instant.now();
        List<SubjectConsentView.ConsentLedgerEntryView> expected = List.of();
        when(jooq.findExpiredGrants(cutoff)).thenReturn(expected);

        assertSame(expected, composite.findExpiredGrants(cutoff));
        verify(redis, never()).findExpiredGrants(any());
    }

    @Test
    @DisplayName("findPolicy always delegates to JOOQ")
    void findPolicyDelegatesToJooq() {
        PurposeView.PolicyView policy = new PurposeView.PolicyView(
                1L, "FIELD", null, new PurposeView.FieldRef(1L, "email", "user"));
        when(jooq.findPolicy("marketing", "FIELD", "email", "user", null)).thenReturn(policy);

        assertSame(policy, composite.findPolicy("marketing", "FIELD", "email", "user", null));
        verify(redis, never()).findPolicy(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ── Plugin SPI ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("isReady delegates to fallback service")
    void isReadyDelegatesToFallback() {
        when(jooq.isReady()).thenReturn(true);
        assertTrue(composite.isReady());

        when(jooq.isReady()).thenReturn(false);
        assertFalse(composite.isReady());
    }
}
