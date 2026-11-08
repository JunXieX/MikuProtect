package dev.junxiex.mikuprotect.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.LookupQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuerySqlTest {

    private IdRegistry registry() {
        IdRegistry registry = new IdRegistry();
        registry.worlds().assign("world");
        registry.users().assign("Steve");
        registry.materials().assign("minecraft:stone");
        return registry;
    }

    @Test
    void lookupAppliesPageAndCap() {
        LookupQuery query = LookupQuery.all().user("Steve").page(2).limit(50);
        QuerySql sql = QuerySql.forLookup(query, registry(), 100);
        assertTrue(sql.pageClause().contains("LIMIT 50 OFFSET 50"));
        assertTrue(sql.whereClause().contains("a.user_id = ?"));
        assertEquals(1, sql.params().size());
    }

    @Test
    void lookupCapsAtConfiguredMaximum() {
        LookupQuery query = LookupQuery.all().limit(500);
        QuerySql sql = QuerySql.forLookup(query, registry(), 100);
        assertTrue(sql.pageClause().contains("LIMIT 100 OFFSET 0"));
    }

    @Test
    void scanIgnoresQueryLimitButKeepsOffset() {
        LookupQuery query = LookupQuery.all().page(1).limit(10);
        QuerySql sql = QuerySql.forScan(query, registry(), 200000);
        assertTrue(sql.pageClause().contains("LIMIT 200000 OFFSET 0"));
    }

    @Test
    void radiusProducesBoundingBoxAndCircle() {
        LookupQuery query = LookupQuery.all().center("world", 10, 64, 10).radius(5);
        QuerySql sql = QuerySql.forLookup(query, registry(), 100);
        assertTrue(sql.whereClause().contains("a.x BETWEEN ? AND ?"));
        assertTrue(sql.whereClause().contains("a.z BETWEEN ? AND ?"));
        assertTrue(sql.whereClause().contains("a.y BETWEEN ? AND ?"));
        assertTrue(sql.whereClause().contains("<= ?"));
    }

    @Test
    void excludedActionsUseNotIn() {
        LookupQuery query = LookupQuery.all().actions(List.of(ActionType.BLOCK_BREAK))
                .excludeActions(List.of(ActionType.PLAYER_CHAT));
        QuerySql sql = QuerySql.forLookup(query, registry(), 100);
        assertTrue(sql.whereClause().contains("a.action IN (?)"));
        assertTrue(sql.whereClause().contains("a.action NOT IN (?)"));
        assertEquals(2, sql.params().size());
    }

    @Test
    void countHasNoOrderOrLimit() {
        QuerySql sql = QuerySql.forCount(LookupQuery.all().user("Steve"), registry());
        assertTrue(sql.orderClause().isEmpty());
        assertTrue(sql.pageClause().isEmpty());
    }

    @Test
    void userLookupIsCaseInsensitive() {
        QuerySql sql = QuerySql.forLookup(LookupQuery.all().user("steve"), registry(), 100);
        assertTrue(sql.whereClause().contains("a.user_id = ?"));
        assertEquals(1, sql.params().size());
    }

    @Test
    void unknownNameFailsClosed() {
        QuerySql sql = QuerySql.forLookup(LookupQuery.all().user("Nobody"), registry(), 100);
        assertTrue(sql.whereClause().contains("1 = 0"));
    }

    @Test
    void searchEscapesLikeWildcards() {
        QuerySql sql = QuerySql.forLookup(LookupQuery.all().search("50%_off!"), registry(), 100);
        String params = sql.params().toString();
        assertTrue(params.contains("50!%!_off!!"));
    }
}
