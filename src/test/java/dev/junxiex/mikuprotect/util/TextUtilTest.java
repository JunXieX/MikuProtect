package dev.junxiex.mikuprotect.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextUtilTest {

    @Test
    void parsesLegacyIntoComponentAndPlainText() {
        assertEquals("hello", TextUtil.plain(TextUtil.component("&ahello")));
        assertEquals("[MikuProtect] ok", TextUtil.plain(TextUtil.PREFIX + "ok"));
    }

    @Test
    void nullTextIsEmpty() {
        assertEquals("", TextUtil.plain((String) null));
    }
}
