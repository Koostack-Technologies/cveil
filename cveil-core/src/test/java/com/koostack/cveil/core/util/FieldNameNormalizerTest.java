package com.koostack.cveil.core.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FieldNameNormalizerTest {

    @Nested
    class ToSnakeCase {

        @ParameterizedTest
        @CsvSource({
            // camelCase
            "emailAddress, email_address",
            "firstName, first_name",
            "dateOfBirth, date_of_birth",
            "ipAddress, ip_address",
            // PascalCase
            "EmailAddress, email_address",
            "FirstName, first_name",
            "DateOfBirth, date_of_birth",
            "CreditCardNumber, credit_card_number",
            // SCREAMING_SNAKE_CASE
            "EMAIL_ADDRESS, email_address",
            "FIRST_NAME, first_name",
            "DATE_OF_BIRTH, date_of_birth",
            "SSN, ssn",
            // kebab-case
            "email-address, email_address",
            "first-name, first_name",
            "date-of-birth, date_of_birth",
            // dot.case
            "email.address, email_address",
            "first.name, first_name",
            // snake_case (no-op)
            "email_address, email_address",
            "first_name, first_name",
            "date_of_birth, date_of_birth",
            "ssn, ssn",
            // Acronyms
            "HTMLParser, html_parser",
            "getHTTPResponse, get_http_response",
            "parseJSON, parse_json",
            "XMLToJSON, xml_to_json",
            // Mixed
            "myField_Name, my_field_name",
            "Some_mixedCase_value, some_mixed_case_value",
            // Entity names
            "Customer, customer",
            "Employee, employee",
            "CUSTOMER, customer",
            "customer, customer",
        })
        void convertsVariousFormatsToSnakeCase(String input, String expected) {
            assertEquals(expected, FieldNameNormalizer.toSnakeCase(input));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void returnsNullForNullOrBlank(String input) {
            assertNull(FieldNameNormalizer.toSnakeCase(input));
        }

        @Test
        void handlesLeadingTrailingUnderscores() {
            assertEquals("email", FieldNameNormalizer.toSnakeCase("_email_"));
            assertEquals("email_address", FieldNameNormalizer.toSnakeCase("__email_address__"));
        }

        @Test
        void collapsesMultipleUnderscores() {
            assertEquals("email_address", FieldNameNormalizer.toSnakeCase("email__address"));
        }

        @Test
        void handlesSingleCharacterInput() {
            assertEquals("a", FieldNameNormalizer.toSnakeCase("a"));
            assertEquals("a", FieldNameNormalizer.toSnakeCase("A"));
        }
    }

    @Nested
    class Normalize {

        @Test
        void normalizesNonBlankInput() {
            assertEquals("email_address", FieldNameNormalizer.normalize("emailAddress"));
            assertEquals("customer", FieldNameNormalizer.normalize("Customer"));
        }

        @Test
        void returnsNullAsIsForNull() {
            assertNull(FieldNameNormalizer.normalize(null));
        }

        @Test
        void returnsBlankAsIsForBlank() {
            assertEquals("", FieldNameNormalizer.normalize(""));
            assertEquals("   ", FieldNameNormalizer.normalize("   "));
        }
    }
}
