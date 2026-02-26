package com.koostack.operio.plugin.resolver.sensitive.annotation;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.annotation.Sensitive;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResolverSensitiveAnnotationTest {

    private ResolverSensitiveAnnotation resolver;

    @BeforeEach
    void setUp() {
        resolver = new ResolverSensitiveAnnotation();
    }

    // ---------------------------------------------------------------
    // Test domain classes — declared as static inner classes so
    // Jackson can introspect them without an enclosing instance.
    // ---------------------------------------------------------------

    /** No @Sensitive annotations at all. */
    public static class PlainPojo {

        private String name;
        private int age;

        public PlainPojo() {}

        public PlainPojo(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    /** Single @Sensitive field with all three attributes set. */
    public static class SingleFieldAnnotated {

        @Sensitive(
            category = "PII",
            fieldName = "emailAddress",
            purpose = "marketing"
        )
        private String email;

        private String nickname;

        public SingleFieldAnnotated() {}

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

    /** @Sensitive annotation on getter instead of field. */
    public static class GetterAnnotated {

        private String ssn;

        public GetterAnnotated() {}

        @Sensitive(category = "PII", purpose = "identity-verification")
        public String getSsn() {
            return ssn;
        }

        public void setSsn(String ssn) {
            this.ssn = ssn;
        }
    }

    /** fieldName defaults to Jackson property name when not specified. */
    public static class DefaultFieldName {

        @Sensitive(category = "HEALTH", purpose = "analytics")
        private String diagnosis;

        public DefaultFieldName() {}

        public String getDiagnosis() {
            return diagnosis;
        }

        public void setDiagnosis(String d) {
            this.diagnosis = d;
        }
    }

    /** Category defaults to empty string when not specified. */
    public static class DefaultCategory {

        @Sensitive(purpose = "billing")
        private String creditCard;

        public DefaultCategory() {}

        public String getCreditCard() {
            return creditCard;
        }

        public void setCreditCard(String cc) {
            this.creditCard = cc;
        }
    }

    /** Multiple annotated fields. */
    public static class MultipleFields {

        @Sensitive(category = "PII", purpose = "marketing")
        private String email;

        @Sensitive(category = "PII", purpose = "identity")
        private String phone;

        private String publicName;

        public MultipleFields() {}

        public String getEmail() {
            return email;
        }

        public void setEmail(String e) {
            this.email = e;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String p) {
            this.phone = p;
        }

        public String getPublicName() {
            return publicName;
        }

        public void setPublicName(String n) {
            this.publicName = n;
        }
    }

    /** Inner class used for nested-object tests. */
    public static class Address {

        @Sensitive(
            category = "PII",
            fieldName = "streetAddress",
            purpose = "shipping"
        )
        private String street;

        private String city;

        public Address() {}

        public String getStreet() {
            return street;
        }

        public void setStreet(String s) {
            this.street = s;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String c) {
            this.city = c;
        }
    }

    /** Outer class containing a nested Address. */
    public static class UserWithAddress {

        @Sensitive(category = "PII", purpose = "contact")
        private String email;

        private Address address;

        public UserWithAddress() {}

        public String getEmail() {
            return email;
        }

        public void setEmail(String e) {
            this.email = e;
        }

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address a) {
            this.address = a;
        }
    }

    /** Circular reference: NodeA → NodeB → NodeA. */
    public static class NodeA {

        @Sensitive(category = "GRAPH", purpose = "traversal")
        private String label;

        private NodeB peer;

        public NodeA() {}

        public String getLabel() {
            return label;
        }

        public void setLabel(String l) {
            this.label = l;
        }

        public NodeB getPeer() {
            return peer;
        }

        public void setPeer(NodeB p) {
            this.peer = p;
        }
    }

    public static class NodeB {

        @Sensitive(category = "GRAPH", purpose = "traversal")
        private String tag;

        private NodeA back;

        public NodeB() {}

        public String getTag() {
            return tag;
        }

        public void setTag(String t) {
            this.tag = t;
        }

        public NodeA getBack() {
            return back;
        }

        public void setBack(NodeA b) {
            this.back = b;
        }
    }

    /** Contains a List<Address> — generic element type should be inspected. */
    public static class UserWithAddressList {

        @Sensitive(category = "PII", purpose = "contact")
        private String name;

        private List<Address> addresses;

        public UserWithAddressList() {}

        public String getName() {
            return name;
        }

        public void setName(String n) {
            this.name = n;
        }

        public List<Address> getAddresses() {
            return addresses;
        }

        public void setAddresses(List<Address> a) {
            this.addresses = a;
        }
    }

    /** Contains a Map<String, Address> — value type should be inspected. */
    public static class UserWithAddressMap {

        private Map<String, Address> addressBook;

        public UserWithAddressMap() {}

        public Map<String, Address> getAddressBook() {
            return addressBook;
        }

        public void setAddressBook(Map<String, Address> m) {
            this.addressBook = m;
        }
    }

    /** Contains an Address[] — array element type should be inspected. */
    public static class UserWithAddressArray {

        private Address[] addresses;

        public UserWithAddressArray() {}

        public Address[] getAddresses() {
            return addresses;
        }

        public void setAddresses(Address[] a) {
            this.addresses = a;
        }
    }

    /** Self-referencing class — should not cause infinite recursion. */
    public static class SelfReferencing {

        @Sensitive(category = "TREE", purpose = "display")
        private String value;

        private SelfReferencing child;

        public SelfReferencing() {}

        public String getValue() {
            return value;
        }

        public void setValue(String v) {
            this.value = v;
        }

        public SelfReferencing getChild() {
            return child;
        }

        public void setChild(SelfReferencing c) {
            this.child = c;
        }
    }

    // ---------------------------------------------------------------
    // Domain classes for path-aware specific tests
    // ---------------------------------------------------------------

    /** Used in the "same field name at different paths" test. */
    public static class Contact {

        @Sensitive(category = "PII", purpose = "notifications")
        private String email;

        public Contact() {}

        public String getEmail() {
            return email;
        }

        public void setEmail(String e) {
            this.email = e;
        }
    }

    /**
     * Has a root-level @Sensitive email AND a nested Contact that also has
     * a @Sensitive email. The two must produce distinct qualified paths.
     */
    public static class UserWithContact {

        @Sensitive(category = "PII", purpose = "marketing")
        private String email;

        private Contact contact;

        public UserWithContact() {}

        public String getEmail() {
            return email;
        }

        public void setEmail(String e) {
            this.email = e;
        }

        public Contact getContact() {
            return contact;
        }

        public void setContact(Contact c) {
            this.contact = c;
        }
    }

    /**
     * Diamond pattern: same Address class appears at two different properties.
     * Both paths must produce scopes.
     */
    public static class UserWithTwoAddresses {

        private Address home;
        private Address work;

        public UserWithTwoAddresses() {}

        public Address getHome() {
            return home;
        }

        public void setHome(Address h) {
            this.home = h;
        }

        public Address getWork() {
            return work;
        }

        public void setWork(Address w) {
            this.work = w;
        }
    }

    /** Deeper nesting: Root → Middle → Leaf. */
    public static class Leaf {

        @Sensitive(category = "DEEP", purpose = "audit")
        private String secret;

        public Leaf() {}

        public String getSecret() {
            return secret;
        }

        public void setSecret(String s) {
            this.secret = s;
        }
    }

    public static class Middle {

        @Sensitive(category = "DEEP", purpose = "audit")
        private String info;

        private Leaf leaf;

        public Middle() {}

        public String getInfo() {
            return info;
        }

        public void setInfo(String i) {
            this.info = i;
        }

        public Leaf getLeaf() {
            return leaf;
        }

        public void setLeaf(Leaf l) {
            this.leaf = l;
        }
    }

    public static class Root {

        private String publicField;
        private Middle middle;

        public Root() {}

        public String getPublicField() {
            return publicField;
        }

        public void setPublicField(String p) {
            this.publicField = p;
        }

        public Middle getMiddle() {
            return middle;
        }

        public void setMiddle(Middle m) {
            this.middle = m;
        }
    }

    /**
     * Inner type with a @Sensitive field that has NO fieldName override, used
     * inside a List. The resolved path should use the Jackson property name.
     */
    public static class Tag {

        @Sensitive(category = "META", purpose = "indexing")
        private String label;

        public Tag() {}

        public String getLabel() {
            return label;
        }

        public void setLabel(String l) {
            this.label = l;
        }
    }

    public static class ItemWithTags {

        private List<Tag> tags;

        public ItemWithTags() {}

        public List<Tag> getTags() {
            return tags;
        }

        public void setTags(List<Tag> t) {
            this.tags = t;
        }
    }

    // ---------------------------------------------------------------
    // Tests — basic / root-level (unchanged expectations)
    // ---------------------------------------------------------------

    @Test
    void nullInput_returnsEmptySet() {
        Set<ConsentScope> scopes = resolver.resolveScopes(null);
        assertNotNull(scopes);
        assertTrue(scopes.isEmpty());
    }

    @Test
    void unannotatedClass_returnsEmptySet() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new PlainPojo("Alice", 30)
        );
        assertNotNull(scopes);
        assertTrue(scopes.isEmpty());
    }

    @Test
    void fieldAnnotation_producesCorrectScope() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SingleFieldAnnotated()
        );

        // Root-level field — path is just the leaf name (fieldName override).
        ConsentScope expected = new ConsentScope(
            "PII",
            "emailAddress",
            "SingleFieldAnnotated",
            "marketing"
        );
        assertTrue(
            scopes.contains(expected),
            "Expected scope " + expected + " but got " + scopes
        );
    }

    @Test
    void getterAnnotation_producesCorrectScope() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new GetterAnnotated()
        );

        // fieldName defaults to "" (blank) → falls back to Jackson property name "ssn".
        ConsentScope expected = new ConsentScope(
            "PII",
            "ssn",
            "GetterAnnotated",
            "identity-verification"
        );
        assertTrue(
            scopes.contains(expected),
            "Expected scope " + expected + " but got " + scopes
        );
    }

    @Test
    void defaultFieldName_usesJacksonPropertyName() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new DefaultFieldName()
        );

        ConsentScope expected = new ConsentScope(
            "HEALTH",
            "diagnosis",
            "DefaultFieldName",
            "analytics"
        );
        assertTrue(
            scopes.contains(expected),
            "Expected scope " + expected + " but got " + scopes
        );
    }

    @Test
    void defaultCategory_usesEmptyString() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new DefaultCategory()
        );

        ConsentScope expected = new ConsentScope(
            "",
            "creditCard",
            "DefaultCategory",
            "billing"
        );
        assertTrue(
            scopes.contains(expected),
            "Expected scope " + expected + " but got " + scopes
        );
    }

    @Test
    void categoryIsPopulated() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SingleFieldAnnotated()
        );

        ConsentScope scope = scopes.iterator().next();
        assertEquals(
            "PII",
            scope.category(),
            "category should be populated from @Sensitive.category()"
        );
    }

    @Test
    void multipleAnnotatedFields_allScopesDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new MultipleFields());

        assertEquals(
            2,
            scopes.size(),
            "Should discover exactly 2 annotated fields"
        );
        assertTrue(
            scopes.contains(
                new ConsentScope("PII", "email", "MultipleFields", "marketing")
            )
        );
        assertTrue(
            scopes.contains(
                new ConsentScope("PII", "phone", "MultipleFields", "identity")
            )
        );
    }

    // ---------------------------------------------------------------
    // Tests — nested objects (path-aware expectations)
    // ---------------------------------------------------------------

    @Test
    void nestedObject_scopesFromBothLevelsDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithAddress()
        );

        // Root-level: email → bare name
        assertTrue(
            scopes.contains(
                new ConsentScope("PII", "email", "UserWithAddress", "contact")
            ),
            "Should find root-level scope; got " + scopes
        );

        // Nested: Address.street via "address" property; @Sensitive has
        // fieldName="streetAddress" override → path = "address.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "address.streetAddress",
                    "UserWithAddress",
                    "shipping"
                )
            ),
            "Nested scope should be qualified with parent path; got " + scopes
        );
    }

    @Test
    void nestedObject_scopeFieldNameIncludesParentPath() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithAddress()
        );

        // The bare "streetAddress" should NOT appear — only the qualified version.
        assertFalse(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "streetAddress",
                    "UserWithAddress",
                    "shipping"
                )
            ),
            "Bare (unqualified) nested field name should not be emitted"
        );
    }

    // ---------------------------------------------------------------
    // Tests — circular references (path-aware)
    // ---------------------------------------------------------------

    @Test
    void circularReference_terminatesWithoutInfiniteLoop() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new NodeA());

        // NodeA.label at root → "label"
        assertTrue(
            scopes.contains(
                new ConsentScope("GRAPH", "label", "NodeA", "traversal")
            ),
            "Should find NodeA's root-level scope; got " + scopes
        );

        // NodeB.tag reached via NodeA.peer → "peer.tag"
        assertTrue(
            scopes.contains(
                new ConsentScope("GRAPH", "peer.tag", "NodeA", "traversal")
            ),
            "NodeB scope should be qualified through 'peer'; got " + scopes
        );
    }

    @Test
    void circularReference_doesNotProduceUnqualifiedNestedName() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new NodeA());

        // The bare "tag" should NOT appear.
        assertFalse(
            scopes.contains(
                new ConsentScope("GRAPH", "tag", "NodeA", "traversal")
            ),
            "Bare 'tag' should not be present — only 'peer.tag'; got " + scopes
        );
    }

    @Test
    void selfReferencingClass_terminatesWithoutInfiniteLoop() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SelfReferencing()
        );

        // Only the root-level "value" should be discovered; the child
        // property is SelfReferencing which is on the ancestor stack → stops.
        assertEquals(1, scopes.size());
        assertTrue(
            scopes.contains(
                new ConsentScope("TREE", "value", "SelfReferencing", "display")
            )
        );
    }

    // ---------------------------------------------------------------
    // Tests — collections, maps, arrays (path-aware)
    // ---------------------------------------------------------------

    @Test
    void listOfAnnotatedType_elementTypeScopesDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithAddressList()
        );

        // Root: name → "name"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "name",
                    "UserWithAddressList",
                    "contact"
                )
            ),
            "Should find scope from outer class; got " + scopes
        );

        // List<Address> via "addresses" property → "addresses.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "addresses.streetAddress",
                    "UserWithAddressList",
                    "shipping"
                )
            ),
            "Should discover @Sensitive fields inside List<Address> with qualified path; got " +
                scopes
        );
    }

    @Test
    void mapOfAnnotatedValueType_valueTypeScopesDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithAddressMap()
        );

        // Map<String, Address> via "addressBook" → "addressBook.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "addressBook.streetAddress",
                    "UserWithAddressMap",
                    "shipping"
                )
            ),
            "Should discover @Sensitive fields inside Map value type with qualified path; got " +
                scopes
        );
    }

    @Test
    void arrayOfAnnotatedType_elementTypeScopesDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithAddressArray()
        );

        // Address[] via "addresses" → "addresses.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "addresses.streetAddress",
                    "UserWithAddressArray",
                    "shipping"
                )
            ),
            "Should discover @Sensitive fields inside Address[] with qualified path; got " +
                scopes
        );
    }

    @Test
    void listElement_noFieldNameOverride_usesPropertyName() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new ItemWithTags());

        // Tag.label via "tags" → "tags.label" (no fieldName override)
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "META",
                    "tags.label",
                    "ItemWithTags",
                    "indexing"
                )
            ),
            "List element field without fieldName override should use property name; got " +
                scopes
        );
    }

    // ---------------------------------------------------------------
    // Tests — path-aware: same field name at different positions
    // ---------------------------------------------------------------

    @Test
    void sameFieldNameDifferentPaths_producesDistinctScopes() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithContact()
        );

        // Root: email → "email"
        assertTrue(
            scopes.contains(
                new ConsentScope("PII", "email", "UserWithContact", "marketing")
            ),
            "Root-level 'email' should be present; got " + scopes
        );

        // Nested Contact.email via "contact" → "contact.email"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "contact.email",
                    "UserWithContact",
                    "notifications"
                )
            ),
            "Nested 'contact.email' should be present; got " + scopes
        );

        // They should be two distinct scopes.
        long emailScopes = scopes
            .stream()
            .filter(
                s ->
                    s.fieldName().equals("email") ||
                    s.fieldName().equals("contact.email")
            )
            .count();
        assertEquals(
            2,
            emailScopes,
            "Root 'email' and 'contact.email' should be distinct scopes"
        );
    }

    @Test
    void sameFieldNameDifferentPaths_noUnqualifiedCollision() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithContact()
        );

        // There should be exactly one scope whose fieldName is "email" (the root one).
        long bareEmailCount = scopes
            .stream()
            .filter(s -> "email".equals(s.fieldName()))
            .count();
        assertEquals(
            1,
            bareEmailCount,
            "Only one scope should have the bare name 'email' (the root-level one)"
        );
    }

    // ---------------------------------------------------------------
    // Tests — path-aware: diamond pattern
    // ---------------------------------------------------------------

    @Test
    void diamondPattern_sameClassAtDifferentPaths_bothDiscovered() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithTwoAddresses()
        );

        // Address via "home" → "home.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "home.streetAddress",
                    "UserWithTwoAddresses",
                    "shipping"
                )
            ),
            "Address fields via 'home' should be qualified; got " + scopes
        );

        // Address via "work" → "work.streetAddress"
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "work.streetAddress",
                    "UserWithTwoAddresses",
                    "shipping"
                )
            ),
            "Address fields via 'work' should be qualified; got " + scopes
        );
    }

    @Test
    void diamondPattern_producesDistinctPathsForSameClass() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new UserWithTwoAddresses()
        );

        // Both qualified paths should be present and distinct.
        assertEquals(
            2,
            scopes.size(),
            "Diamond pattern should produce 2 scopes (one per path); got " +
                scopes
        );
    }

    // ---------------------------------------------------------------
    // Tests — deeper nesting (3 levels)
    // ---------------------------------------------------------------

    @Test
    void deepNesting_thirdLevelFieldHasFullyQualifiedPath() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new Root());

        // Middle.info via "middle" → "middle.info"
        assertTrue(
            scopes.contains(
                new ConsentScope("DEEP", "middle.info", "Root", "audit")
            ),
            "Second-level field should be 'middle.info'; got " + scopes
        );

        // Leaf.secret via "middle.leaf" → "middle.leaf.secret"
        assertTrue(
            scopes.contains(
                new ConsentScope("DEEP", "middle.leaf.secret", "Root", "audit")
            ),
            "Third-level field should be 'middle.leaf.secret'; got " + scopes
        );
    }

    @Test
    void deepNesting_noUnqualifiedLeafNames() {
        Set<ConsentScope> scopes = resolver.resolveScopes(new Root());

        assertFalse(
            scopes.stream().anyMatch(s -> "secret".equals(s.fieldName())),
            "Bare 'secret' should not be emitted — only 'middle.leaf.secret'"
        );
        assertFalse(
            scopes.stream().anyMatch(s -> "info".equals(s.fieldName())),
            "Bare 'info' should not be emitted — only 'middle.info'"
        );
    }

    // ---------------------------------------------------------------
    // Tests — primitives, java.* fields, metadata
    // ---------------------------------------------------------------

    @Test
    void primitiveAndJavaLangFields_notRecursedInto() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new PlainPojo("Bob", 25)
        );
        assertTrue(scopes.isEmpty());
    }

    @Test
    void getName_returnsSensitiveAnnotation() {
        assertEquals("sensitive-annotation", resolver.getName());
    }

    @Test
    void isReady_returnsTrue() {
        assertTrue(resolver.isReady());
    }

    // ---------------------------------------------------------------
    // Tests — configuration
    // ---------------------------------------------------------------

    @Test
    void configure_withNullConfig_doesNotThrow() {
        assertDoesNotThrow(() -> resolver.configure(null));
    }

    @Test
    void configure_withDefaultConfig_doesNotThrow() {
        assertDoesNotThrow(() ->
            resolver.configure(new ResolverSensitiveAnnotationConfig())
        );
    }

    @Test
    void configure_withCustomObjectMapper_doesNotThrow() {
        var customMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertDoesNotThrow(() ->
            resolver.configure(
                new ResolverSensitiveAnnotationConfig(customMapper)
            )
        );

        // After reconfiguration, the resolver should still work correctly.
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SingleFieldAnnotated()
        );
        assertTrue(
            scopes.contains(
                new ConsentScope(
                    "PII",
                    "emailAddress",
                    "SingleFieldAnnotated",
                    "marketing"
                )
            )
        );
    }

    // ---------------------------------------------------------------
    // Tests — fieldName override behaviour
    // ---------------------------------------------------------------

    @Test
    void fieldAnnotation_overridesFieldName() {
        // SingleFieldAnnotated has fieldName="emailAddress" on a field called "email"
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SingleFieldAnnotated()
        );

        for (ConsentScope scope : scopes) {
            if ("marketing".equals(scope.purposeName())) {
                assertEquals(
                    "emailAddress",
                    scope.fieldName(),
                    "fieldName from @Sensitive should override the property name"
                );
                return;
            }
        }
        fail("Did not find the expected 'marketing' purpose scope");
    }

    @Test
    void nonAnnotatedFieldsDoNotProduceScopes() {
        Set<ConsentScope> scopes = resolver.resolveScopes(
            new SingleFieldAnnotated()
        );

        // Only 1 scope should be produced (for email), not for nickname.
        assertEquals(
            1,
            scopes.size(),
            "Only annotated fields should produce scopes"
        );
    }
}
