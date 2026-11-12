package duke.ktest;

import static duke.ktest.Assert.assertEquals;
import static duke.ktest.Assert.assertThrows;

final class StringTest {

    static void testReplaceCharRejectsNonLatin1ReplacementWhenMatched() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> "\u00e9".replace('\u00e9', '\u0100'), "non-Latin-1 replacement");
        assertEquals("non-Latin-1 char 256 (Strings are Latin-1 only for now)", e.getMessage(),
                "replacement exception message");
    }
}
