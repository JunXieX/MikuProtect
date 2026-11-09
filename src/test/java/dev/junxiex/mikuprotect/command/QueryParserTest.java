package dev.junxiex.mikuprotect.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junxiex.mikuprotect.config.MikuConfig;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class QueryParserTest {

    private static QueryParser parser() {
        return new QueryParser(new MikuConfig(new YamlConfiguration(), Logger.getAnonymousLogger()));
    }

    @Test
    void globalTokenRemovesWorldFilter() {
        QueryParser.Result result = parser().parse("#global u:Steve", null, false);
        assertTrue(result.ok());
        assertNull(result.query().worldName);
        assertEquals("Steve", result.query().userName);
    }

    @Test
    void worldTokenStillFiltersARealWorld() {
        QueryParser.Result result = parser().parse("#world_nether", null, false);
        assertTrue(result.ok());
        assertEquals("world_nether", result.query().worldName);
    }
}
