package com.koostack.operio.core.models;

import java.util.Collections;
import java.util.Set;
/**
 * The result of a sensitive-field discovery scan.
 *
 * <p>Contains the full set of discovered fields (with their entity and
 * category assignments) and a convenience set of all distinct category
 * names found during the scan.
 *
 * <p>Consumers use this to register the discovered metadata into the
 * consent admin schema via {@link com.koostack.operio.core.TenantService}
 * registration methods.
 *
 * @param fields     every discovered sensitive field, possibly empty
 * @param categories the distinct category names extracted from the
 *                   discovered fields, possibly empty
 */
public record DiscoveryResult(
    Set<DiscoveredField> fields,
    Set<String> categories
) {
    public DiscoveryResult {
        fields =
            fields != null
                ? Collections.unmodifiableSet(fields)
                : Collections.emptySet();
        categories =
            categories != null
                ? Collections.unmodifiableSet(categories)
                : Collections.emptySet();
    }

    /** Returns an empty discovery result. */
    public static DiscoveryResult empty() {
        return new DiscoveryResult(
            Collections.emptySet(),
            Collections.emptySet()
        );
    }
}
