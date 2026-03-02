package com.koostack.operio.rest.site;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * Serves the public-facing Operio website pages using Qute templates.
 *
 * <p>These endpoints are unauthenticated (listed in
 * {@code ApiKeyAuthFilter.isPublicPath}) and produce HTML responses.
 */
@Path("/")
public class SiteResource {

    @Inject
    Template index;

    @Inject
    Template docs;

    @Inject
    Template plans;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance landing() {
        return index.instance();
    }

    @GET
    @Path("docs")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance documentation() {
        return docs.instance();
    }

    @GET
    @Path("plans")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pricing() {
        return plans.data("tiers", pricingTiers());
    }

    private List<Map<String, Object>> pricingTiers() {
        return List.of(
            Map.ofEntries(
                Map.entry("name", "Starter"),
                Map.entry("price", "$49"),
                Map.entry("period", "/mo"),
                Map.entry("description", "For early-stage startups validating product-market fit."),
                Map.entry("evaluations", "50,000"),
                Map.entry("overage", "$0.002 / eval"),
                Map.entry("masking", "Basic (email, phone)"),
                Map.entry("auditRetention", "30 days"),
                Map.entry("support", "Community + Docs"),
                Map.entry("residency", "Shared US / EU"),
                Map.entry("sso", false),
                Map.entry("sla", "99.9%"),
                Map.entry("cta", "Start Free Trial"),
                Map.entry("highlighted", false)
            ),
            Map.ofEntries(
                Map.entry("name", "Pro"),
                Map.entry("price", "$249"),
                Map.entry("period", "/mo"),
                Map.entry("description", "For growing teams that need compliance at scale."),
                Map.entry("evaluations", "500,000"),
                Map.entry("overage", "$0.001 / eval"),
                Map.entry("masking", "Full field-level"),
                Map.entry("auditRetention", "1 year"),
                Map.entry("support", "Priority email · 24h SLA"),
                Map.entry("residency", "Dedicated region"),
                Map.entry("sso", true),
                Map.entry("sla", "99.95%"),
                Map.entry("cta", "Start Free Trial"),
                Map.entry("highlighted", true)
            ),
            Map.ofEntries(
                Map.entry("name", "Enterprise"),
                Map.entry("price", "Custom"),
                Map.entry("period", ""),
                Map.entry("description", "For organizations with strict regulatory requirements."),
                Map.entry("evaluations", "Unlimited"),
                Map.entry("overage", "Custom"),
                Map.entry("masking", "Full + custom rules"),
                Map.entry("auditRetention", "Unlimited"),
                Map.entry("support", "Dedicated CSM · Slack · 1h SLA"),
                Map.entry("residency", "Custom / on-prem"),
                Map.entry("sso", true),
                Map.entry("sla", "99.99%"),
                Map.entry("cta", "Contact Sales"),
                Map.entry("highlighted", false)
            )
        );
    }
}
