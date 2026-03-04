package com.koostack.operio.rest.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits the SmallRye-generated OpenAPI specification into two filtered
 * documents: one for tenant-facing (non-platform) endpoints and one for
 * platform administration endpoints.
 *
 * <p>The split is based on the request path prefix. Any path starting with
 * {@code /api/v1/platform} is considered a platform endpoint; everything
 * else is a tenant endpoint. Unused tags and the docs endpoints themselves
 * are stripped from each filtered spec.
 *
 * <p>Both specs are computed lazily on first access by fetching the full
 * spec from the built-in {@code /q/openapi} endpoint and cached for the
 * lifetime of the application.
 */
@ApplicationScoped
public class OpenApiSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiSplitService.class);

    private static final String PLATFORM_PATH_PREFIX = "/api/v1/platform";
    private static final String DOCS_PATH_PREFIX = "/api/v1/docs";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "quarkus.smallrye-openapi.path", defaultValue = "/q/openapi")
    String openApiPath;

    private volatile String tenantSpecJson;
    private volatile String platformSpecJson;

    /**
     * Returns the OpenAPI spec JSON containing only non-platform endpoints.
     */
    public String getTenantSpec() {
        ensureInitialized();
        return tenantSpecJson;
    }

    /**
     * Returns the OpenAPI spec JSON containing only platform endpoints.
     */
    public String getPlatformSpec() {
        ensureInitialized();
        return platformSpecJson;
    }

    private synchronized void ensureInitialized() {
        if (tenantSpecJson != null) {
            return;
        }
        try {
            String fullJson = fetchOpenApiSpec();
            ObjectNode fullTree = (ObjectNode) MAPPER.readTree(fullJson);

            tenantSpecJson = buildFilteredSpec(fullTree, false);
            platformSpecJson = buildFilteredSpec(fullTree, true);
            LOG.info("OpenAPI specs split — tenant and platform docs ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to split OpenAPI spec", e);
        }
    }

    /**
     * Fetches the full OpenAPI spec from the built-in SmallRye endpoint.
     * Called lazily on first request, so the HTTP server is guaranteed to
     * be running.
     */
    private String fetchOpenApiSpec() throws Exception {
        URI uri = URI.create("http://localhost:" + httpPort + openApiPath + "?format=json");
        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "Failed to fetch OpenAPI spec from " + uri + " — HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String buildFilteredSpec(ObjectNode fullTree, boolean platformOnly)
            throws Exception {
        ObjectNode tree = fullTree.deepCopy();

        // Customize title and description per audience
        ObjectNode info = (ObjectNode) tree.get("info");
        if (info != null && platformOnly) {
            info.put("title", "Operio Platform Administration API");
            info.put("description",
                "Platform administration endpoints for tenant lifecycle "
                    + "and API key management. Requires the super-admin role "
                    + "or a valid bootstrap key.");
        }

        // Filter paths: keep platform or non-platform, and always exclude docs paths
        filterPaths(tree, platformOnly);

        // Remove tags not referenced by remaining paths
        cleanupTags(tree);

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }

    private void filterPaths(ObjectNode tree, boolean keepPlatform) {
        ObjectNode paths = (ObjectNode) tree.get("paths");
        if (paths == null) {
            return;
        }

        List<String> toRemove = new ArrayList<>();
        var it = paths.fieldNames();
        while (it.hasNext()) {
            String path = it.next();
            // Always exclude docs endpoints from both specs
            if (path.equals(DOCS_PATH_PREFIX)
                    || path.startsWith(DOCS_PATH_PREFIX + "/")
                    || path.equals(PLATFORM_PATH_PREFIX + "/docs")
                    || path.startsWith(PLATFORM_PATH_PREFIX + "/docs/")) {
                toRemove.add(path);
                continue;
            }
            boolean isPlatform = path.startsWith(PLATFORM_PATH_PREFIX);
            if (keepPlatform != isPlatform) {
                toRemove.add(path);
            }
        }
        toRemove.forEach(paths::remove);
    }

    private void cleanupTags(ObjectNode tree) {
        // Collect tags referenced by the remaining paths
        Set<String> usedTags = new HashSet<>();
        ObjectNode paths = (ObjectNode) tree.get("paths");
        if (paths != null) {
            paths.fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(method -> {
                    JsonNode tags = method.getValue().get("tags");
                    if (tags != null && tags.isArray()) {
                        tags.forEach(tag -> usedTags.add(tag.asText()));
                    }
                })
            );
        }

        // Filter the top-level tags array to only include used tags
        ArrayNode tags = (ArrayNode) tree.get("tags");
        if (tags != null) {
            ArrayNode filtered = MAPPER.createArrayNode();
            tags.forEach(tag -> {
                JsonNode name = tag.get("name");
                if (name != null && usedTags.contains(name.asText())) {
                    filtered.add(tag);
                }
            });
            tree.set("tags", filtered);
        }
    }
}
