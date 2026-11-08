package dev.junxiex.mikuprotect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LookupQueryTest {

    @Test
    void radiusNeedsBothHorizontalCoordinates() {
        LookupQuery query = LookupQuery.all().radius(5);
        query.centerX = 10;
        assertFalse(query.hasRadius());
        query.centerZ = 20;
        assertTrue(query.hasRadius());
    }

    @Test
    void copyIsIndependent() {
        LookupQuery query = LookupQuery.all().user("Steve").limit(7);
        LookupQuery copy = query.copy();
        copy.user("Alex").limit(3);
        assertEquals("Steve", query.userName);
        assertEquals(7, query.limit);
        assertEquals("Alex", copy.userName);
        assertEquals(3, copy.limit);
    }

    @Test
    void radiusQueryUsesCenterWorld() {
        LookupQuery query = LookupQuery.all().center("world", 10, 64, 20).radius(5);
        assertEquals("world", query.centerWorld);
        assertTrue(query.hasRadius());
    }
}
