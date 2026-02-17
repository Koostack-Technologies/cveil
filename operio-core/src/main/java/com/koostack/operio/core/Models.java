package com.koostack.operio.core;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public interface Models {

    public record IdentityContext(String user, String token) {}

    public record ConsentScope(String fieldName, String purposeName) {
    /**
     * Factory method for cleaner syntax in the Aspect logic.
     */
    public static ConsentScope to(String fieldName, String forPurpose) {
        return new ConsentScope(fieldName, forPurpose);
    }

}


    public record ConsentResponse (String subject, Set<ConsentScope> granted, Set<ConsentScope> denied, Map<String, String> reasons) {
        public static ConsentResponse to(String subject,Set<ConsentScope> granted, Set<ConsentScope> denied, Map<String, String> reasons) {
            return new ConsentResponse(subject, granted, denied, reasons);
        }
    }

}
