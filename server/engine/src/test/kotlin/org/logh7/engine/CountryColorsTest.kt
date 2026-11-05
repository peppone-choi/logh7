package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryColorsTest {
    @Test fun `color overrides preserve inherited colors and accept only six RGB digits`() {
        assertNull(CountryColors.parse("inherit"))
        assertEquals(0,CountryColors.parse("#000000"))
        assertEquals(0xffffff,CountryColors.parse("#fFfFfF"))
        assertEquals("#ABCDEF",CountryColors.hex(0xabcdef))
        for(invalid in listOf("red","#123","#12345678","url(image)","#GG0000","", "#123456\n"))
            assertFailsWith<IllegalArgumentException>(invalid){CountryColors.parse(invalid)}
        assertFailsWith<IllegalArgumentException>{CountryColors(primary=-1)}
        assertFailsWith<IllegalArgumentException>{CountryColors(fleet=0x1000000)}
    }
}
