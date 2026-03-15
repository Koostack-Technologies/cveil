package com.koostack.cveil.rest;

import static org.mockito.Mockito.*;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.filter.BillableMetricFilter;
import com.koostack.cveil.rest.util.MetricsHelper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillableMetricFilter")
class BillableMetricFilterTest {

    @Mock private CveilRequestHolder requestHolder;
    @Mock private MetricsHelper metrics;
    @Mock private ContainerRequestContext request;
    @Mock private ContainerResponseContext response;
    @Mock private UriInfo uriInfo;

    private BillableMetricFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new BillableMetricFilter();
        inject(filter, "requestHolder", requestHolder);
        inject(filter, "metrics", metrics);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private IdentityContext patIdentity(String tenantId) {
        return new IdentityContext(
                "user1",
                "cveil_test",
                "user@example.com",
                Set.of("consent:write"),
                Map.of(),
                tenantId,
                "access-token");
    }

    private IdentityContext jwtIdentity(String tenantId) {
        return new IdentityContext(
                "user1",
                "jwt-token",
                "user@example.com",
                Set.of("consent:write"),
                Map.of(),
                tenantId,
                "keycloak");
    }

    private void givenPath(String path) {
        lenient().when(request.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getPath()).thenReturn(path);
    }

    private void givenMethod(String method) {
        lenient().when(request.getMethod()).thenReturn(method);
    }

    private void givenStatus(int status) {
        lenient().when(response.getStatus()).thenReturn(status);
    }

    @Nested
    @DisplayName("PAT on billable endpoint → metric published")
    class PatBillable {

        @Test
        @DisplayName("POST to consent endpoint emits metric")
        void consentWrite() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/subjects/consent/grant");
            givenMethod("POST");
            givenStatus(200);

            filter.filter(request, response);

            verify(metrics)
                    .counter(
                            eq(BillableMetricFilter.METRIC_NAME),
                            eq("t1"),
                            eq(
                                    Map.of(
                                            "resource",
                                            "consent",
                                            "method",
                                            "POST",
                                            "auth_type",
                                            "pat")));
        }

        @Test
        @DisplayName("GET to governance endpoint emits metric")
        void governanceRead() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t2"));
            givenPath("/api/v1/governance/purposes");
            givenMethod("GET");
            givenStatus(200);

            filter.filter(request, response);

            verify(metrics)
                    .counter(
                            eq(BillableMetricFilter.METRIC_NAME),
                            eq("t2"),
                            eq(
                                    Map.of(
                                            "resource",
                                            "governance",
                                            "method",
                                            "GET",
                                            "auth_type",
                                            "pat")));
        }

        @Test
        @DisplayName("DELETE to catalogue endpoint emits metric")
        void catalogueDelete() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t3"));
            givenPath("/api/v1/catalogue/fields/user/email");
            givenMethod("DELETE");
            givenStatus(200);

            filter.filter(request, response);

            verify(metrics)
                    .counter(
                            eq(BillableMetricFilter.METRIC_NAME),
                            eq("t3"),
                            eq(
                                    Map.of(
                                            "resource",
                                            "catalogue",
                                            "method",
                                            "DELETE",
                                            "auth_type",
                                            "pat")));
        }
    }

    @Nested
    @DisplayName("JWT on billable endpoint → NO metric")
    class JwtBillable {

        @Test
        @DisplayName("POST to consent via JWT does not emit metric")
        void jwtConsentWrite() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(jwtIdentity("t1"));
            givenPath("/api/v1/subjects/consent/grant");
            givenMethod("POST");

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("GET to governance via JWT does not emit metric")
        void jwtGovernanceRead() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(jwtIdentity("t1"));
            givenPath("/api/v1/governance/purposes");
            givenMethod("GET");

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("PAT on non-billable endpoint → NO metric")
    class PatNonBillable {

        @Test
        @DisplayName("PAT to billing endpoint does not emit metric")
        void patBilling() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/billing/subscription");
            givenMethod("GET");

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("Unauthenticated → NO metric")
    class Unauthenticated {

        @Test
        @DisplayName("unauthenticated request does not emit metric")
        void unauthenticated() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(false);

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("Path without leading slash is normalized")
    class PathNormalization {

        @Test
        @DisplayName("path without leading slash still resolves")
        void noLeadingSlash() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("api/v1/governance/purposes");
            givenMethod("GET");
            givenStatus(200);

            filter.filter(request, response);

            verify(metrics)
                    .counter(
                            eq(BillableMetricFilter.METRIC_NAME),
                            eq("t1"),
                            eq(
                                    Map.of(
                                            "resource",
                                            "governance",
                                            "method",
                                            "GET",
                                            "auth_type",
                                            "pat")));
        }
    }

    @Nested
    @DisplayName("Error responses are NOT metered")
    class ErrorResponses {

        @Test
        @DisplayName("400 Bad Request is not metered")
        void badRequest() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/subjects/consent/grant");
            givenMethod("POST");
            givenStatus(400);

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("404 Not Found is not metered")
        void notFound() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/governance/purposes/nonexistent");
            givenMethod("GET");
            givenStatus(404);

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("500 Internal Server Error is not metered")
        void serverError() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/catalogue/fields");
            givenMethod("GET");
            givenStatus(500);

            filter.filter(request, response);

            verify(metrics, never()).counter(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("201 Created IS metered")
        void created() throws IOException {
            when(requestHolder.isAuthenticated()).thenReturn(true);
            when(requestHolder.identity()).thenReturn(patIdentity("t1"));
            givenPath("/api/v1/subjects/consent/grant");
            givenMethod("POST");
            givenStatus(201);

            filter.filter(request, response);

            verify(metrics)
                    .counter(
                            eq(BillableMetricFilter.METRIC_NAME),
                            eq("t1"),
                            eq(
                                    Map.of(
                                            "resource",
                                            "consent",
                                            "method",
                                            "POST",
                                            "auth_type",
                                            "pat")));
        }
    }
}
