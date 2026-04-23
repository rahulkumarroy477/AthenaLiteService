package org.example.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InputValidatorTest {

    // --- userId ---
    @Test
    void validEmail() {
        assertTrue(InputValidator.isValidUserId("user@example.com"));
    }

    @Test
    void validSimpleUserId() {
        assertTrue(InputValidator.isValidUserId("user123"));
    }

    @Test
    void rejectsNullUserId() {
        assertFalse(InputValidator.isValidUserId(null));
    }

    @Test
    void rejectsBlankUserId() {
        assertFalse(InputValidator.isValidUserId(""));
        assertFalse(InputValidator.isValidUserId("   "));
    }

    @Test
    void rejectsPathTraversalUserId() {
        assertFalse(InputValidator.isValidUserId("../../etc/passwd"));
    }

    @Test
    void rejectsSlashInUserId() {
        assertFalse(InputValidator.isValidUserId("user/admin"));
    }

    @Test
    void rejectsSpaceInUserId() {
        assertFalse(InputValidator.isValidUserId("user name"));
    }

    // --- tableName ---
    @Test
    void validTableName() {
        assertTrue(InputValidator.isValidTableName("sales_data"));
    }

    @Test
    void rejectsNullTableName() {
        assertFalse(InputValidator.isValidTableName(null));
    }

    @Test
    void rejectsTableNameWithDots() {
        assertFalse(InputValidator.isValidTableName("table.name"));
    }

    @Test
    void rejectsTableNameWithSlash() {
        assertFalse(InputValidator.isValidTableName("table/name"));
    }

    @Test
    void rejectsTooLongTableName() {
        assertFalse(InputValidator.isValidTableName("a".repeat(129)));
    }

    @Test
    void acceptsMaxLengthTableName() {
        assertTrue(InputValidator.isValidTableName("a".repeat(128)));
    }

    // --- queryId ---
    @Test
    void validQueryId() {
        assertTrue(InputValidator.isValidQueryId("qr_1714000000000"));
    }

    @Test
    void rejectsNullQueryId() {
        assertFalse(InputValidator.isValidQueryId(null));
    }

    @Test
    void rejectsQueryIdWithoutPrefix() {
        assertFalse(InputValidator.isValidQueryId("1714000000000"));
    }

    @Test
    void rejectsQueryIdWithPathTraversal() {
        assertFalse(InputValidator.isValidQueryId("../../hack"));
    }

    @Test
    void rejectsQueryIdWithLetters() {
        assertFalse(InputValidator.isValidQueryId("qr_abc123"));
    }

    // --- sanitizeTableName ---
    @Test
    void sanitizesSpecialChars() {
        assertEquals("my_table_1", InputValidator.sanitizeTableName("my-table 1"));
    }

    @Test
    void sanitizesNull() {
        assertNull(InputValidator.sanitizeTableName(null));
    }
}
