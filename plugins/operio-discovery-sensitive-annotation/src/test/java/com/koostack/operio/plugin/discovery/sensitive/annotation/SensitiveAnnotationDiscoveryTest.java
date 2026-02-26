package com.koostack.operio.plugin.discovery.sensitive.annotation;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.DiscoveredField;
import com.koostack.operio.core.models.DiscoveryResult;
import com.koostack.operio.core.annotation.Sensitive;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SensitiveAnnotationDiscoveryTest {

    private SensitiveAnnotationDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new SensitiveAnnotationDiscovery();
    }

    // -----------------------------------------------------------------------
    // Test domain classes
    // -----------------------------------------------------------------------

    public static class User {

        @Sensitive(category = "PII", purpose = "contact", fieldName = "email")
        private String emailAddress;

        @Sensitive(category = "PII", purpose = "identity")
        private String name;

        private String nickname; // not sensitive

        private Address home;
        private Address work;

        public User() {}

        public String getEmailAddress() {
            return emailAddress;
        }

        public void setEmailAddress(String emailAddress) {
            this.emailAddress = emailAddress;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public Address getHome() {
            return home;
        }

        public void setHome(Address home) {
            this.home = home;
        }

        public Address getWork() {
            return work;
        }

        public void setWork(Address work) {
            this.work = work;
        }
    }

    public static class Address {

        @Sensitive(category = "PII", purpose = "shipping")
        private String street;

        private String city; // not sensitive

        public Address() {}

        public String getStreet() {
            return street;
        }

        public void setStreet(String street) {
            this.street = street;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    public static class Patient {

        @Sensitive(category = "HEALTH", purpose = "treatment")
        private String diagnosis;

        @Sensitive(category = "PII", purpose = "identity")
        private String ssn;

        public Patient() {}

        public String getDiagnosis() {
            return diagnosis;
        }

        public void setDiagnosis(String diagnosis) {
            this.diagnosis = diagnosis;
        }

        public String getSsn() {
            return ssn;
        }

        public void setSsn(String ssn) {
            this.ssn = ssn;
        }
    }

    public static class EmptyClass {

        private String notAnnotated;
        private int count;

        public EmptyClass() {}

        public String getNotAnnotated() {
            return notAnnotated;
        }

        public void setNotAnnotated(String notAnnotated) {
            this.notAnnotated = notAnnotated;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class GetterAnnotated {

        private String secret;

        public GetterAnnotated() {}

        @Sensitive(category = "CREDENTIALS", purpose = "auth")
        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class WithCollection {

        private List<Patient> patients;

        public WithCollection() {}

        public List<Patient> getPatients() {
            return patients;
        }

        public void setPatients(List<Patient> patients) {
            this.patients = patients;
        }
    }

    public static class SelfReferencing {

        @Sensitive(category = "PII", purpose = "identity")
        private String id;

        private SelfReferencing parent;

        public SelfReferencing() {}

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public SelfReferencing getParent() {
            return parent;
        }

        public void setParent(SelfReferencing parent) {
            this.parent = parent;
        }
    }

    public static class NoCategoryOrPurpose {

        @Sensitive
        private String rawField;

        public NoCategoryOrPurpose() {}

        public String getRawField() {
            return rawField;
        }

        public void setRawField(String rawField) {
            this.rawField = rawField;
        }
    }

    // -----------------------------------------------------------------------
    // Tests — basic discovery
    // -----------------------------------------------------------------------

    @Test
    void discoverFindsAnnotatedFieldsOnSingleClass() {
        discovery.configure(AnnotationDiscoveryConfig.of(User.class));
        DiscoveryResult result = discovery.discover();

        assertNotNull(result);
        assertFalse(result.fields().isEmpty());

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        // "email" comes from @Sensitive(fieldName = "email") override
        assertTrue(
            fieldNames.contains("email"),
            "Should find field with overridden name 'email'"
        );
        // "name" comes from the property name
        assertTrue(fieldNames.contains("name"), "Should find field 'name'");
    }

    @Test
    void discoverIgnoresUnannotatedFields() {
        discovery.configure(AnnotationDiscoveryConfig.of(User.class));
        DiscoveryResult result = discovery.discover();

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        // "nickname" is not annotated
        assertFalse(
            fieldNames.contains("nickname"),
            "Should not find unannotated field 'nickname'"
        );
    }

    @Test
    void discoverFindsNestedAnnotatedFields() {
        discovery.configure(AnnotationDiscoveryConfig.of(User.class));
        DiscoveryResult result = discovery.discover();

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        // Address.street should be found at both home.street and work.street paths
        assertTrue(
            fieldNames.contains("home.street"),
            "Should find nested field 'home.street', found: " + fieldNames
        );
        assertTrue(
            fieldNames.contains("work.street"),
            "Should find nested field 'work.street', found: " + fieldNames
        );
    }

    // -----------------------------------------------------------------------
    // Tests — categories
    // -----------------------------------------------------------------------

    @Test
    void discoverCollectsDistinctCategories() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(User.class, Patient.class)
        );
        DiscoveryResult result = discovery.discover();

        assertTrue(
            result.categories().contains("PII"),
            "Should discover PII category"
        );
        assertTrue(
            result.categories().contains("HEALTH"),
            "Should discover HEALTH category"
        );
    }

    @Test
    void categoriesDoNotContainBlanks() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(NoCategoryOrPurpose.class)
        );
        DiscoveryResult result = discovery.discover();

        for (String cat : result.categories()) {
            assertFalse(
                cat.isBlank(),
                "Categories should not contain blank strings"
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tests — entity name
    // -----------------------------------------------------------------------

    @Test
    void entityNameIsRootClassSimpleName() {
        discovery.configure(AnnotationDiscoveryConfig.of(Patient.class));
        DiscoveryResult result = discovery.discover();

        for (DiscoveredField field : result.fields()) {
            assertEquals(
                "Patient",
                field.entityName(),
                "Entity name should be the root class simple name"
            );
        }
    }

    @Test
    void nestedFieldsUseRootEntityName() {
        discovery.configure(AnnotationDiscoveryConfig.of(User.class));
        DiscoveryResult result = discovery.discover();

        DiscoveredField homeStreet = result
            .fields()
            .stream()
            .filter(f -> "home.street".equals(f.fieldName()))
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "home.street not found in: " +
                        result
                            .fields()
                            .stream()
                            .map(DiscoveredField::fieldName)
                            .toList()
                )
            );

        assertEquals(
            "User",
            homeStreet.entityName(),
            "Nested field should use root entity name 'User', not 'Address'"
        );
    }

    // -----------------------------------------------------------------------
    // Tests — multiple root classes
    // -----------------------------------------------------------------------

    @Test
    void discoverScansMultipleClasses() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(User.class, Patient.class)
        );
        DiscoveryResult result = discovery.discover();

        Set<String> entityNames = result
            .fields()
            .stream()
            .map(DiscoveredField::entityName)
            .collect(Collectors.toSet());

        assertTrue(entityNames.contains("User"), "Should scan User");
        assertTrue(entityNames.contains("Patient"), "Should scan Patient");
    }

    // -----------------------------------------------------------------------
    // Tests — empty / edge cases
    // -----------------------------------------------------------------------

    @Test
    void discoverReturnsEmptyForNoAnnotations() {
        discovery.configure(AnnotationDiscoveryConfig.of(EmptyClass.class));
        DiscoveryResult result = discovery.discover();

        assertTrue(result.fields().isEmpty(), "Should return no fields");
        assertTrue(
            result.categories().isEmpty(),
            "Should return no categories"
        );
    }

    @Test
    void discoverReturnsEmptyWhenNotConfigured() {
        // No configure() call — scanClasses is empty
        DiscoveryResult result = discovery.discover();

        assertNotNull(result);
        assertTrue(result.fields().isEmpty());
        assertTrue(result.categories().isEmpty());
    }

    @Test
    void discoverReturnsEmptyResultObject() {
        DiscoveryResult empty = DiscoveryResult.empty();

        assertNotNull(empty);
        assertTrue(empty.fields().isEmpty());
        assertTrue(empty.categories().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Tests — getter annotations
    // -----------------------------------------------------------------------

    @Test
    void discoverFindsAnnotationsOnGetters() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(GetterAnnotated.class)
        );
        DiscoveryResult result = discovery.discover();

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        assertTrue(
            fieldNames.contains("secret"),
            "Should find getter-annotated field 'secret'"
        );
        assertTrue(result.categories().contains("CREDENTIALS"));
    }

    // -----------------------------------------------------------------------
    // Tests — collection element types
    // -----------------------------------------------------------------------

    @Test
    void discoverInspectsCollectionElementTypes() {
        discovery.configure(AnnotationDiscoveryConfig.of(WithCollection.class));
        DiscoveryResult result = discovery.discover();

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        // Patient fields should be found under the "patients" path
        assertTrue(
            fieldNames.contains("patients.diagnosis"),
            "Should find field inside collection element type: patients.diagnosis, found: " +
                fieldNames
        );
        assertTrue(
            fieldNames.contains("patients.ssn"),
            "Should find field inside collection element type: patients.ssn, found: " +
                fieldNames
        );
    }

    // -----------------------------------------------------------------------
    // Tests — circular references
    // -----------------------------------------------------------------------

    @Test
    void discoverHandlesSelfReferencingClassWithoutInfiniteLoop() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(SelfReferencing.class)
        );
        DiscoveryResult result = discovery.discover();

        // Should complete without stack overflow
        assertNotNull(result);
        assertFalse(
            result.fields().isEmpty(),
            "Should find the @Sensitive id field"
        );

        Set<String> fieldNames = result
            .fields()
            .stream()
            .map(DiscoveredField::fieldName)
            .collect(Collectors.toSet());

        assertTrue(
            fieldNames.contains("id"),
            "Should find 'id' at root level, found: " + fieldNames
        );
    }

    // -----------------------------------------------------------------------
    // Tests — purpose and description
    // -----------------------------------------------------------------------

    @Test
    void discoveredFieldsCarryPurpose() {
        discovery.configure(AnnotationDiscoveryConfig.of(Patient.class));
        DiscoveryResult result = discovery.discover();

        DiscoveredField diagnosis = result
            .fields()
            .stream()
            .filter(f -> "diagnosis".equals(f.fieldName()))
            .findFirst()
            .orElseThrow(() ->
                new AssertionError(
                    "diagnosis not found in: " +
                        result
                            .fields()
                            .stream()
                            .map(DiscoveredField::fieldName)
                            .toList()
                )
            );

        assertEquals("treatment", diagnosis.purpose());
    }

    @Test
    void discoveredFieldsHaveAutoGeneratedDescription() {
        discovery.configure(AnnotationDiscoveryConfig.of(Patient.class));
        DiscoveryResult result = discovery.discover();

        for (DiscoveredField field : result.fields()) {
            assertNotNull(
                field.description(),
                "Description should be auto-generated"
            );
            assertTrue(
                field.description().contains("@Sensitive annotation"),
                "Description should mention the annotation source"
            );
            assertTrue(
                field.description().contains(field.fieldName()),
                "Description should contain the field name"
            );
        }
    }

    @Test
    void blankCategoryNormalizedToNull() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(NoCategoryOrPurpose.class)
        );
        DiscoveryResult result = discovery.discover();

        assertFalse(
            result.fields().isEmpty(),
            "Should still discover the field"
        );
        DiscoveredField rawField = result.fields().iterator().next();
        // @Sensitive with default category="" should be normalized to null
        assertNull(
            rawField.category(),
            "Blank category should be normalized to null"
        );
    }

    @Test
    void blankPurposeNormalizedToNull() {
        discovery.configure(
            AnnotationDiscoveryConfig.of(NoCategoryOrPurpose.class)
        );
        DiscoveryResult result = discovery.discover();

        assertFalse(
            result.fields().isEmpty(),
            "Should discover the @Sensitive field even with blank defaults"
        );
        DiscoveredField rawField = result.fields().iterator().next();
        // @Sensitive with default purpose="" should be normalized to null
        assertNull(
            rawField.purpose(),
            "Blank purpose should be normalized to null"
        );
    }

    // -----------------------------------------------------------------------
    // Tests — plugin metadata
    // -----------------------------------------------------------------------

    @Test
    void pluginNameIsSensitiveAnnotationDiscovery() {
        assertEquals("sensitive-annotation-discovery", discovery.getName());
    }

    @Test
    void pluginPriorityIs100() {
        assertEquals(100, discovery.getPriority());
    }

    @Test
    void configurationTypeIsAnnotationDiscoveryConfig() {
        assertEquals(
            AnnotationDiscoveryConfig.class,
            discovery.getConfigurationType()
        );
    }

    // -----------------------------------------------------------------------
    // Tests — idempotency
    // -----------------------------------------------------------------------

    @Test
    void discoverIsIdempotent() {
        discovery.configure(AnnotationDiscoveryConfig.of(User.class));
        DiscoveryResult first = discovery.discover();
        DiscoveryResult second = discovery.discover();

        assertEquals(
            first.fields().size(),
            second.fields().size(),
            "Repeated discover() calls should return the same number of fields"
        );
        assertEquals(
            first.categories(),
            second.categories(),
            "Repeated discover() calls should return the same categories"
        );
    }

    // -----------------------------------------------------------------------
    // Tests — AnnotationDiscoveryConfig
    // -----------------------------------------------------------------------

    @Test
    void configRejectsNull() {
        assertThrows(NullPointerException.class, () ->
            new AnnotationDiscoveryConfig(null)
        );
    }

    @Test
    void configVarargsFactory() {
        AnnotationDiscoveryConfig config = AnnotationDiscoveryConfig.of(
            User.class,
            Patient.class
        );
        assertEquals(2, config.scanClasses().size());
        assertTrue(config.scanClasses().contains(User.class));
        assertTrue(config.scanClasses().contains(Patient.class));
    }

    @Test
    void configScanClassesIsUnmodifiable() {
        AnnotationDiscoveryConfig config = AnnotationDiscoveryConfig.of(
            User.class
        );
        assertThrows(UnsupportedOperationException.class, () ->
            config.scanClasses().add(Patient.class)
        );
    }

    // -----------------------------------------------------------------------
    // Tests — DiscoveredField model
    // -----------------------------------------------------------------------

    @Test
    void discoveredFieldRejectsNullFieldName() {
        assertThrows(NullPointerException.class, () ->
            new DiscoveredField(null, "Entity", "PII", "purpose", "desc")
        );
    }

    @Test
    void discoveredFieldRejectsBlankFieldName() {
        assertThrows(IllegalArgumentException.class, () ->
            new DiscoveredField("   ", "Entity", "PII", "purpose", "desc")
        );
    }

    @Test
    void discoveredFieldOfFactoryWorksWithThreeArgs() {
        DiscoveredField field = DiscoveredField.of("email", "User", "PII");
        assertEquals("email", field.fieldName());
        assertEquals("User", field.entityName());
        assertEquals("PII", field.category());
        assertNull(field.purpose());
        assertNull(field.description());
    }

    @Test
    void discoveredFieldOfFactoryWorksWithFiveArgs() {
        DiscoveredField field = DiscoveredField.of(
            "email",
            "User",
            "PII",
            "marketing",
            "User email"
        );
        assertEquals("email", field.fieldName());
        assertEquals("User", field.entityName());
        assertEquals("PII", field.category());
        assertEquals("marketing", field.purpose());
        assertEquals("User email", field.description());
    }

    // -----------------------------------------------------------------------
    // Tests — DiscoveryResult model
    // -----------------------------------------------------------------------

    @Test
    void discoveryResultHandlesNullInputs() {
        DiscoveryResult result = new DiscoveryResult(null, null);
        assertNotNull(result.fields());
        assertNotNull(result.categories());
        assertTrue(result.fields().isEmpty());
        assertTrue(result.categories().isEmpty());
    }

    @Test
    void discoveryResultFieldsAreUnmodifiable() {
        DiscoveryResult result = new DiscoveryResult(
            Set.of(DiscoveredField.of("x", "E", "C")),
            Set.of("C")
        );
        assertThrows(UnsupportedOperationException.class, () ->
            result.fields().add(DiscoveredField.of("y", "E2", "C2"))
        );
    }

    @Test
    void discoveryResultCategoriesAreUnmodifiable() {
        DiscoveryResult result = new DiscoveryResult(
            Set.of(DiscoveredField.of("x", "E", "C")),
            Set.of("C")
        );
        assertThrows(UnsupportedOperationException.class, () ->
            result.categories().add("NEW")
        );
    }
}
