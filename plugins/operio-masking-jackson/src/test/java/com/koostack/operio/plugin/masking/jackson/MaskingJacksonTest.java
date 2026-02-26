package com.koostack.operio.plugin.masking.jackson;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.exception.OperioServiceException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaskingJacksonTest {

    private MaskingJackson masking;

    @BeforeEach
    void setUp() {
        masking = new MaskingJackson();
    }

    // ---------------------------------------------------------------
    // Test domain classes
    // ---------------------------------------------------------------

    public static class StringFields {

        private String email;
        private String name;

        public StringFields() {}

        public StringFields(String email, String name) {
            this.email = email;
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class MixedFields {

        private String label;
        private int age;
        private boolean active;
        private double score;
        private long count;

        public MixedFields() {}

        public MixedFields(
            String label,
            int age,
            boolean active,
            double score,
            long count
        ) {
            this.label = label;
            this.age = age;
            this.active = active;
            this.score = score;
            this.count = count;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }

    public static class Inner {

        private String secret;
        private String visible;

        public Inner() {}

        public Inner(String secret, String visible) {
            this.secret = secret;
            this.visible = visible;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String s) {
            this.secret = s;
        }

        public String getVisible() {
            return visible;
        }

        public void setVisible(String v) {
            this.visible = v;
        }
    }

    public static class Outer {

        private String name;
        private Inner nested;

        public Outer() {}

        public Outer(String name, Inner nested) {
            this.name = name;
            this.nested = nested;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Inner getNested() {
            return nested;
        }

        public void setNested(Inner nested) {
            this.nested = nested;
        }
    }

    public static class WithArray {

        private String title;
        private List<Inner> items;

        public WithArray() {}

        public WithArray(String title, List<Inner> items) {
            this.title = title;
            this.items = items;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String t) {
            this.title = t;
        }

        public List<Inner> getItems() {
            return items;
        }

        public void setItems(List<Inner> items) {
            this.items = items;
        }
    }

    public static class WithObjectField {

        private String tag;
        private Inner child;

        public WithObjectField() {}

        public WithObjectField(String tag, Inner child) {
            this.tag = tag;
            this.child = child;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String t) {
            this.tag = t;
        }

        public Inner getChild() {
            return child;
        }

        public void setChild(Inner c) {
            this.child = c;
        }
    }

    public static class BooleanHolder {

        private boolean flag;
        private String info;

        public BooleanHolder() {}

        public BooleanHolder(boolean flag, String info) {
            this.flag = flag;
            this.info = info;
        }

        public boolean isFlag() {
            return flag;
        }

        public void setFlag(boolean f) {
            this.flag = f;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String i) {
            this.info = i;
        }
    }

    public static class DoubleHolder {

        private double temperature;
        private String unit;

        public DoubleHolder() {}

        public DoubleHolder(double temperature, String unit) {
            this.temperature = temperature;
            this.unit = unit;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double t) {
            this.temperature = t;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String u) {
            this.unit = u;
        }
    }

    /**
     * Two nested objects that both contain a field named "email".
     * Used to verify path-aware masking only hits the right one.
     */
    public static class Contact {

        private String email;
        private String phone;

        public Contact() {}

        public Contact(String email, String phone) {
            this.email = email;
            this.phone = phone;
        }

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
    }

    public static class UserWithContact {

        private String email;
        private Contact contact;

        public UserWithContact() {}

        public UserWithContact(String email, Contact contact) {
            this.email = email;
            this.contact = contact;
        }

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

    /** Three-level nesting for deep path tests. */
    public static class Leaf {

        private String secret;
        private int code;

        public Leaf() {}

        public Leaf(String secret, int code) {
            this.secret = secret;
            this.code = code;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String s) {
            this.secret = s;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int c) {
            this.code = c;
        }
    }

    public static class Middle {

        private String info;
        private Leaf leaf;

        public Middle() {}

        public Middle(String info, Leaf leaf) {
            this.info = info;
            this.leaf = leaf;
        }

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

    public static class DeepRoot {

        private String publicField;
        private Middle middle;

        public DeepRoot() {}

        public DeepRoot(String publicField, Middle middle) {
            this.publicField = publicField;
            this.middle = middle;
        }

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

    /** Two children of the same type at different properties. */
    public static class TwoInners {

        private Inner alpha;
        private Inner beta;

        public TwoInners() {}

        public TwoInners(Inner alpha, Inner beta) {
            this.alpha = alpha;
            this.beta = beta;
        }

        public Inner getAlpha() {
            return alpha;
        }

        public void setAlpha(Inner a) {
            this.alpha = a;
        }

        public Inner getBeta() {
            return beta;
        }

        public void setBeta(Inner b) {
            this.beta = b;
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static ConsentScope scope(String fieldName) {
        return new ConsentScope("", fieldName, null, "");
    }

    private static ConsentScope scope(
        String category,
        String fieldName,
        String purpose
    ) {
        return new ConsentScope(category, fieldName, null, purpose);
    }

    // ---------------------------------------------------------------
    // Tests — null / empty input
    // ---------------------------------------------------------------

    @Test
    void nullInput_returnsNull() {
        Object result = masking.mask(null, Set.of(scope("email")));
        assertNull(result);
    }

    @Test
    void nullDeniedSet_returnsObjectUnchanged() {
        StringFields input = new StringFields("alice@example.com", "Alice");
        Object result = masking.mask(input, null);

        assertSame(
            input,
            result,
            "When deniedScopes is null, the original object should be returned"
        );
    }

    @Test
    void emptyDeniedSet_returnsObjectUnchanged() {
        StringFields input = new StringFields("alice@example.com", "Alice");
        Object result = masking.mask(input, Collections.emptySet());

        assertSame(
            input,
            result,
            "When deniedScopes is empty, the original object should be returned"
        );
    }

    // ---------------------------------------------------------------
    // Tests — root-level type-safe masking
    // ---------------------------------------------------------------

    @Test
    void stringFieldMasked_valueBecomesStars() {
        StringFields input = new StringFields("alice@example.com", "Alice");
        Object result = masking.mask(input, Set.of(scope("email")));

        assertInstanceOf(StringFields.class, result);
        StringFields masked = (StringFields) result;
        assertEquals(
            "***",
            masked.getEmail(),
            "Denied string field should be masked"
        );
        assertEquals(
            "Alice",
            masked.getName(),
            "Non-denied field should be preserved"
        );
    }

    @Test
    void intFieldMasked_valueBecomesZero() {
        MixedFields input = new MixedFields("test", 42, true, 9.5, 100L);
        Object result = masking.mask(input, Set.of(scope("age")));

        assertInstanceOf(MixedFields.class, result);
        MixedFields masked = (MixedFields) result;
        assertEquals(0, masked.getAge(), "Denied int field should become 0");
        assertEquals(
            "test",
            masked.getLabel(),
            "Non-denied string should be preserved"
        );
        assertTrue(masked.isActive(), "Non-denied boolean should be preserved");
        assertEquals(
            9.5,
            masked.getScore(),
            0.001,
            "Non-denied double should be preserved"
        );
    }

    @Test
    void booleanFieldMasked_valueBecomesFalse() {
        BooleanHolder input = new BooleanHolder(true, "flag-info");
        Object result = masking.mask(input, Set.of(scope("flag")));

        assertInstanceOf(BooleanHolder.class, result);
        BooleanHolder masked = (BooleanHolder) result;
        assertFalse(
            masked.isFlag(),
            "Denied boolean field should become false"
        );
        assertEquals(
            "flag-info",
            masked.getInfo(),
            "Non-denied field should be preserved"
        );
    }

    @Test
    void doubleFieldMasked_valueBecomesZero() {
        DoubleHolder input = new DoubleHolder(98.6, "Fahrenheit");
        Object result = masking.mask(input, Set.of(scope("temperature")));

        assertInstanceOf(DoubleHolder.class, result);
        DoubleHolder masked = (DoubleHolder) result;
        assertEquals(
            0.0,
            masked.getTemperature(),
            0.001,
            "Denied double field should become 0.0"
        );
        assertEquals(
            "Fahrenheit",
            masked.getUnit(),
            "Non-denied field should be preserved"
        );
    }

    @Test
    void longFieldMasked_valueBecomesZero() {
        MixedFields input = new MixedFields("test", 10, false, 1.1, 999_999L);
        Object result = masking.mask(input, Set.of(scope("count")));

        assertInstanceOf(MixedFields.class, result);
        MixedFields masked = (MixedFields) result;
        assertEquals(0, masked.getCount(), "Denied long field should become 0");
    }

    @Test
    void objectFieldMasked_valueBecomesNull() {
        Inner child = new Inner("top-secret", "open");
        WithObjectField input = new WithObjectField("myTag", child);
        // Deny the whole "child" object at root level.
        Object result = masking.mask(input, Set.of(scope("child")));

        assertInstanceOf(WithObjectField.class, result);
        WithObjectField masked = (WithObjectField) result;
        assertNull(masked.getChild(), "Denied object field should become null");
        assertEquals(
            "myTag",
            masked.getTag(),
            "Non-denied field should be preserved"
        );
    }

    @Test
    void nonDeniedFieldsPreserved() {
        MixedFields input = new MixedFields("hello", 25, true, 3.14, 42L);
        // Mask only "label" — everything else should survive.
        Object result = masking.mask(input, Set.of(scope("label")));

        assertInstanceOf(MixedFields.class, result);
        MixedFields masked = (MixedFields) result;
        assertEquals("***", masked.getLabel());
        assertEquals(25, masked.getAge());
        assertTrue(masked.isActive());
        assertEquals(3.14, masked.getScore(), 0.001);
        assertEquals(42L, masked.getCount());
    }

    @Test
    void multipleDeniedScopes_allMatchingFieldsMasked() {
        MixedFields input = new MixedFields("hi", 30, true, 5.5, 100L);
        Set<ConsentScope> denied = Set.of(
            scope("label"),
            scope("age"),
            scope("active")
        );

        Object result = masking.mask(input, denied);
        MixedFields masked = (MixedFields) result;

        assertEquals("***", masked.getLabel());
        assertEquals(0, masked.getAge());
        assertFalse(masked.isActive());
        // Non-denied
        assertEquals(5.5, masked.getScore(), 0.001);
        assertEquals(100L, masked.getCount());
    }

    @Test
    void deniedScopeForNonExistentField_noError() {
        StringFields input = new StringFields("alice@example.com", "Alice");

        // "nonExistent" doesn't match any field — should not cause an error.
        assertDoesNotThrow(() ->
            masking.mask(input, Set.of(scope("nonExistent")))
        );

        Object result = masking.mask(input, Set.of(scope("nonExistent")));
        assertInstanceOf(StringFields.class, result);
        StringFields masked = (StringFields) result;
        assertEquals(
            "alice@example.com",
            masked.getEmail(),
            "No field should be masked"
        );
        assertEquals("Alice", masked.getName());
    }

    // ---------------------------------------------------------------
    // Tests — path-aware nested object masking
    // ---------------------------------------------------------------

    @Test
    void nestedObject_qualifiedPath_masksCorrectField() {
        Inner inner = new Inner("hidden", "shown");
        Outer input = new Outer("outerName", inner);

        // Deny "nested.secret" — the qualified path to the secret field
        // inside the nested Inner object.
        Object result = masking.mask(input, Set.of(scope("nested.secret")));

        assertInstanceOf(Outer.class, result);
        Outer masked = (Outer) result;
        assertEquals(
            "outerName",
            masked.getName(),
            "Outer field should be preserved"
        );
        assertNotNull(masked.getNested(), "Nested object should still exist");
        assertEquals(
            "***",
            masked.getNested().getSecret(),
            "Nested denied field should be masked"
        );
        assertEquals(
            "shown",
            masked.getNested().getVisible(),
            "Nested non-denied field should be preserved"
        );
    }

    @Test
    void nestedObject_bareFieldName_doesNotMaskNestedField() {
        Inner inner = new Inner("hidden", "shown");
        Outer input = new Outer("outerName", inner);

        // Deny bare "secret" — should NOT match "nested.secret" because
        // the masker is now path-aware.
        Object result = masking.mask(input, Set.of(scope("secret")));

        assertInstanceOf(Outer.class, result);
        Outer masked = (Outer) result;
        assertNotNull(masked.getNested());
        assertEquals(
            "hidden",
            masked.getNested().getSecret(),
            "Bare 'secret' should NOT match the nested field 'nested.secret'"
        );
    }

    @Test
    void nestedObject_rootFieldNotAffectedByNestedDenial() {
        Inner inner = new Inner("hidden", "shown");
        Outer input = new Outer("outerName", inner);

        // Deny "nested.secret" — the root-level "name" should be untouched.
        Object result = masking.mask(input, Set.of(scope("nested.secret")));

        Outer masked = (Outer) result;
        assertEquals("outerName", masked.getName());
    }

    // ---------------------------------------------------------------
    // Tests — path-aware array masking
    // ---------------------------------------------------------------

    @Test
    void arrayOfObjects_qualifiedPath_masksFieldInEachElement() {
        List<Inner> items = List.of(
            new Inner("s1", "v1"),
            new Inner("s2", "v2"),
            new Inner("s3", "v3")
        );
        WithArray input = new WithArray("list-title", items);

        // Deny "items.secret" — the qualified path through the array property.
        Object result = masking.mask(input, Set.of(scope("items.secret")));

        assertInstanceOf(WithArray.class, result);
        WithArray masked = (WithArray) result;
        assertEquals("list-title", masked.getTitle());
        assertNotNull(masked.getItems());
        assertEquals(3, masked.getItems().size());

        for (int i = 0; i < masked.getItems().size(); i++) {
            Inner item = masked.getItems().get(i);
            assertEquals(
                "***",
                item.getSecret(),
                "secret field in array element " + i + " should be masked"
            );
            assertEquals(
                "v" + (i + 1),
                item.getVisible(),
                "visible field in array element " + i + " should be preserved"
            );
        }
    }

    @Test
    void arrayOfObjects_bareFieldName_doesNotMaskArrayElementFields() {
        List<Inner> items = List.of(new Inner("s1", "v1"));
        WithArray input = new WithArray("list-title", items);

        // Deny bare "secret" — should NOT match "items.secret".
        Object result = masking.mask(input, Set.of(scope("secret")));

        WithArray masked = (WithArray) result;
        assertEquals(
            "s1",
            masked.getItems().get(0).getSecret(),
            "Bare 'secret' should not match inside array elements"
        );
    }

    // ---------------------------------------------------------------
    // Tests — path-aware: same field name at different levels
    // ---------------------------------------------------------------

    @Test
    void sameFieldName_onlyRootMasked_nestedPreserved() {
        Contact contact = new Contact("nested@example.com", "555-1234");
        UserWithContact input = new UserWithContact(
            "root@example.com",
            contact
        );

        // Deny only the root "email".
        Object result = masking.mask(input, Set.of(scope("email")));

        assertInstanceOf(UserWithContact.class, result);
        UserWithContact masked = (UserWithContact) result;
        assertEquals("***", masked.getEmail(), "Root 'email' should be masked");
        assertEquals(
            "nested@example.com",
            masked.getContact().getEmail(),
            "Nested 'contact.email' should be PRESERVED when only root 'email' is denied"
        );
    }

    @Test
    void sameFieldName_onlyNestedMasked_rootPreserved() {
        Contact contact = new Contact("nested@example.com", "555-1234");
        UserWithContact input = new UserWithContact(
            "root@example.com",
            contact
        );

        // Deny only the nested "contact.email".
        Object result = masking.mask(input, Set.of(scope("contact.email")));

        assertInstanceOf(UserWithContact.class, result);
        UserWithContact masked = (UserWithContact) result;
        assertEquals(
            "root@example.com",
            masked.getEmail(),
            "Root 'email' should be PRESERVED when only 'contact.email' is denied"
        );
        assertEquals(
            "***",
            masked.getContact().getEmail(),
            "Nested 'contact.email' should be masked"
        );
    }

    @Test
    void sameFieldName_bothDenied_bothMasked() {
        Contact contact = new Contact("nested@example.com", "555-1234");
        UserWithContact input = new UserWithContact(
            "root@example.com",
            contact
        );

        // Deny both paths.
        Object result = masking.mask(
            input,
            Set.of(scope("email"), scope("contact.email"))
        );

        UserWithContact masked = (UserWithContact) result;
        assertEquals("***", masked.getEmail(), "Root email should be masked");
        assertEquals(
            "***",
            masked.getContact().getEmail(),
            "Nested contact.email should be masked"
        );
    }

    // ---------------------------------------------------------------
    // Tests — path-aware: diamond pattern (same type at two properties)
    // ---------------------------------------------------------------

    @Test
    void diamondPattern_maskOnlyOneProperty() {
        Inner alpha = new Inner("alpha-secret", "alpha-vis");
        Inner beta = new Inner("beta-secret", "beta-vis");
        TwoInners input = new TwoInners(alpha, beta);

        // Deny only "alpha.secret" — beta.secret should be preserved.
        Object result = masking.mask(input, Set.of(scope("alpha.secret")));

        assertInstanceOf(TwoInners.class, result);
        TwoInners masked = (TwoInners) result;
        assertEquals(
            "***",
            masked.getAlpha().getSecret(),
            "alpha.secret should be masked"
        );
        assertEquals(
            "beta-secret",
            masked.getBeta().getSecret(),
            "beta.secret should be PRESERVED"
        );
    }

    @Test
    void diamondPattern_maskBothProperties() {
        Inner alpha = new Inner("alpha-secret", "alpha-vis");
        Inner beta = new Inner("beta-secret", "beta-vis");
        TwoInners input = new TwoInners(alpha, beta);

        Object result = masking.mask(
            input,
            Set.of(scope("alpha.secret"), scope("beta.secret"))
        );

        TwoInners masked = (TwoInners) result;
        assertEquals("***", masked.getAlpha().getSecret());
        assertEquals("***", masked.getBeta().getSecret());
        // visible fields preserved
        assertEquals("alpha-vis", masked.getAlpha().getVisible());
        assertEquals("beta-vis", masked.getBeta().getVisible());
    }

    // ---------------------------------------------------------------
    // Tests — path-aware: deep nesting (3 levels)
    // ---------------------------------------------------------------

    @Test
    void deepNesting_thirdLevelField_maskedByFullPath() {
        Leaf leaf = new Leaf("top-secret", 42);
        Middle middle = new Middle("mid-info", leaf);
        DeepRoot input = new DeepRoot("public", middle);

        // Deny "middle.leaf.secret" — the fully-qualified 3-level path.
        Object result = masking.mask(
            input,
            Set.of(scope("middle.leaf.secret"))
        );

        assertInstanceOf(DeepRoot.class, result);
        DeepRoot masked = (DeepRoot) result;
        assertEquals("public", masked.getPublicField());
        assertEquals("mid-info", masked.getMiddle().getInfo());
        assertEquals(
            "***",
            masked.getMiddle().getLeaf().getSecret(),
            "Third-level field should be masked by full path"
        );
        assertEquals(
            42,
            masked.getMiddle().getLeaf().getCode(),
            "Non-denied third-level field should be preserved"
        );
    }

    @Test
    void deepNesting_secondLevelField_maskedByQualifiedPath() {
        Leaf leaf = new Leaf("top-secret", 42);
        Middle middle = new Middle("mid-info", leaf);
        DeepRoot input = new DeepRoot("public", middle);

        // Deny "middle.info" only.
        Object result = masking.mask(input, Set.of(scope("middle.info")));

        DeepRoot masked = (DeepRoot) result;
        assertEquals("***", masked.getMiddle().getInfo());
        // Third-level should be untouched.
        assertEquals("top-secret", masked.getMiddle().getLeaf().getSecret());
        assertEquals(42, masked.getMiddle().getLeaf().getCode());
    }

    @Test
    void deepNesting_bareLeafName_doesNotMatchDeepField() {
        Leaf leaf = new Leaf("top-secret", 42);
        Middle middle = new Middle("mid-info", leaf);
        DeepRoot input = new DeepRoot("public", middle);

        // Deny bare "secret" — should NOT match "middle.leaf.secret".
        Object result = masking.mask(input, Set.of(scope("secret")));

        DeepRoot masked = (DeepRoot) result;
        assertEquals(
            "top-secret",
            masked.getMiddle().getLeaf().getSecret(),
            "Bare 'secret' should not match deeply nested 'middle.leaf.secret'"
        );
    }

    @Test
    void deepNesting_multiplePathsDenied() {
        Leaf leaf = new Leaf("top-secret", 42);
        Middle middle = new Middle("mid-info", leaf);
        DeepRoot input = new DeepRoot("public", middle);

        Object result = masking.mask(
            input,
            Set.of(
                scope("middle.info"),
                scope("middle.leaf.secret"),
                scope("middle.leaf.code")
            )
        );

        DeepRoot masked = (DeepRoot) result;
        assertEquals("public", masked.getPublicField());
        assertEquals("***", masked.getMiddle().getInfo());
        assertEquals("***", masked.getMiddle().getLeaf().getSecret());
        assertEquals(0, masked.getMiddle().getLeaf().getCode());
    }

    // ---------------------------------------------------------------
    // Tests — round-trip fidelity
    // ---------------------------------------------------------------

    @Test
    void roundTrip_returnedObjectIsSameClassAsInput() {
        MixedFields input = new MixedFields("data", 10, true, 2.5, 7L);
        Object result = masking.mask(input, Set.of(scope("label")));

        assertNotNull(result);
        assertEquals(
            MixedFields.class,
            result.getClass(),
            "Masked object should be the same class as the input"
        );
    }

    @Test
    void roundTrip_nonMaskedFieldsRetainExactValues() {
        MixedFields input = new MixedFields(
            "data",
            10,
            true,
            2.718281828,
            9_876_543_210L
        );
        Object result = masking.mask(input, Set.of(scope("label")));

        MixedFields masked = (MixedFields) result;
        assertEquals(10, masked.getAge());
        assertTrue(masked.isActive());
        assertEquals(2.718281828, masked.getScore(), 1e-9);
        assertEquals(9_876_543_210L, masked.getCount());
    }

    // ---------------------------------------------------------------
    // Tests — scope matching semantics
    // ---------------------------------------------------------------

    @Test
    void scopesUseFieldNameForMatching_categoryAndPurposeIgnored() {
        // The masking logic extracts fieldName from ConsentScope for matching.
        // Different category/purpose values should still match the same field.
        StringFields input = new StringFields("alice@example.com", "Alice");
        Set<ConsentScope> denied = Set.of(scope("PII", "email", "marketing"));

        Object result = masking.mask(input, denied);
        StringFields masked = (StringFields) result;
        assertEquals(
            "***",
            masked.getEmail(),
            "Masking should match by fieldName regardless of category/purpose"
        );
    }

    // ---------------------------------------------------------------
    // Tests — metadata
    // ---------------------------------------------------------------

    @Test
    void getName_returnsJackson() {
        assertEquals("jackson", masking.getName());
    }

    @Test
    void isReady_returnsTrue() {
        assertTrue(masking.isReady());
    }

    // ---------------------------------------------------------------
    // Tests — configuration
    // ---------------------------------------------------------------

    @Test
    void configure_withNullConfig_doesNotThrow() {
        assertDoesNotThrow(() -> masking.configure(null));
    }

    @Test
    void configure_withDefaultConfig_doesNotThrow() {
        assertDoesNotThrow(() -> masking.configure(new MaskingJacksonConfig()));
    }

    @Test
    void configure_withCustomObjectMapper_isUsedForSubsequentMasks() {
        ObjectMapper customMapper = new ObjectMapper();
        masking.configure(new MaskingJacksonConfig(customMapper));

        // Should still work correctly after reconfiguration.
        StringFields input = new StringFields("bob@example.com", "Bob");
        Object result = masking.mask(input, Set.of(scope("email")));

        assertInstanceOf(StringFields.class, result);
        StringFields masked = (StringFields) result;
        assertEquals("***", masked.getEmail());
        assertEquals("Bob", masked.getName());
    }

    // ---------------------------------------------------------------
    // Tests — OperioServiceException on failure
    // ---------------------------------------------------------------

    /**
     * A class that cannot be round-tripped via Jackson (no default constructor
     * and no @JsonCreator), which should trigger an OperioServiceException
     * during treeToValue.
     */
    public static class Unconvertible {

        private final String value;

        // No default constructor — Jackson cannot deserialise back.
        public Unconvertible(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @Test
    void maskingFailure_throwsOperioServiceException() {
        Unconvertible input = new Unconvertible("test");
        Set<ConsentScope> denied = Set.of(scope("value"));

        assertThrows(
            OperioServiceException.class,
            () -> masking.mask(input, denied),
            "Masking failure should throw OperioServiceException, not raw RuntimeException"
        );
    }

    @Test
    void maskingFailure_exceptionMessageContainsContext() {
        Unconvertible input = new Unconvertible("test");
        Set<ConsentScope> denied = Set.of(scope("value"));

        OperioServiceException ex = assertThrows(
            OperioServiceException.class,
            () -> masking.mask(input, denied)
        );
        assertTrue(
            ex.getMessage().contains("Operio Masking Failure"),
            "Exception message should contain 'Operio Masking Failure'"
        );
        assertNotNull(
            ex.getCause(),
            "Exception should wrap the original cause"
        );
    }
}
