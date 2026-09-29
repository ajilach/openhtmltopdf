package com.openhtmltopdf.hyphenation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.TreeSet;

import org.junit.Test;

public class HyphenationDictionaryTest {

    /** The policy the platform uses (remainCharacterCount="2"); the policy itself is not this module's. */
    private static final int KEEP = 2;

    private static HyphenationDictionary bundled(String language) {
        Optional<HyphenationDictionary> dictionary = HyphenationDictionary.forLanguage(language);
        assertTrue("bundled dictionary for " + language, dictionary.isPresent());
        return dictionary.get();
    }

    private static String split(HyphenationDictionary dictionary, String word) {
        return split(dictionary, word, KEEP, KEEP);
    }

    private static String split(HyphenationDictionary dictionary, String word, int left, int right) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int position : dictionary.breakPoints(word, left, right)) {
            out.append(word, last, position).append('-');
            last = position;
        }
        return out.append(word.substring(last)).toString();
    }

    private static HyphenationDictionary read(String content, String charset) throws IOException {
        return HyphenationDictionary.read(new ByteArrayInputStream(content.getBytes(charset)));
    }

    private static void assertUnreadable(byte[] content) {
        try {
            HyphenationDictionary.read(new ByteArrayInputStream(content));
            fail("expected an IOException");
        } catch (IOException expected) {
            // the only failure a caller has to handle
        }
        assertFalse(HyphenationDictionary.load(new ByteArrayInputStream(content), "test").isPresent());
    }

    // ---- German: hyph-utf8 hyph-de-1996 (MIT) ----------------------------------------------------

    @Test
    public void germanSplitsAreTheMitSetsAndNotTheMergedLevelsOfTheOldFile() {
        HyphenationDictionary de = bundled("de");
        // The old LibreOffice file, read with its two levels merged, gave
        // Deut-sch-land, Be-da-rf, Ba-r-wert, Amtss-pra-che, Ban-k-aus-künf-ten.
        assertEquals("Deutsch-land", split(de, "Deutschland"));
        assertEquals("Be-darf", split(de, "Bedarf"));
        assertEquals("Bar-wert", split(de, "Barwert"));
        assertEquals("Amts-spra-che", split(de, "Amtssprache"));
        assertEquals("Bank-aus-künf-ten", split(de, "Bankauskünften"));
    }

    @Test
    public void germanMoreWords() {
        HyphenationDictionary de = bundled("de");
        assertEquals("Un-ter-schrift", split(de, "Unterschrift"));
        assertEquals("Ver-mö-gens-ver-wal-tung", split(de, "Vermögensverwaltung"));
        assertEquals("Kon-to-in-ha-ber", split(de, "Kontoinhaber"));
    }

    @Test
    public void caseDoesNotMatter() {
        HyphenationDictionary de = bundled("de");
        assertEquals("DEUTSCH-LAND", split(de, "DEUTSCHLAND"));
        assertEquals("Bank-aus-KÜNF-ten", split(de, "BankausKÜNFten"));
    }

    @Test
    public void leftAndRightMinimumsKeepTheEndsWhole() {
        HyphenationDictionary de = bundled("de");
        assertArrayEquals(new int[] {4, 7, 11}, de.breakPoints("Bankauskünften", 2, 2));
        assertArrayEquals(new int[] {7}, de.breakPoints("Bankauskünften", 5, 4));
        assertArrayEquals(new int[0], de.breakPoints("Bankauskünften", 20, 20));
    }

    // ---- Spanish: hyph-utf8 hyph-es (MIT) -------------------------------------------------------

    @Test
    public void spanishSplits() {
        HyphenationDictionary es = bundled("es");
        assertEquals("in-for-ma-ción", split(es, "información"));
        assertEquals("cuen-ta", split(es, "cuenta"));
        assertEquals("fir-ma", split(es, "firma"));
        assertEquals("do-cu-men-ta-ción", split(es, "documentación"));
        assertEquals("re-pre-sen-tan-te", split(es, "representante"));
        assertEquals("trans-fe-ren-cia", split(es, "transferencia"));
    }

    // ---- English: LibreOffice hyph_en_US.dic (Knuth + TUGboat) -----------------------------------

    @Test
    public void englishSplits() {
        HyphenationDictionary en = bundled("en");
        assertEquals("hy-phen-ation", split(en, "hyphenation"));
        assertEquals("in-for-ma-tion", split(en, "information"));
        assertEquals("ac-count", split(en, "account"));
        assertEquals("sig-na-ture", split(en, "signature"));
        assertEquals("ben-e-fi-cial", split(en, "beneficial"));
        assertEquals("rep-re-sen-ta-tive", split(en, "representative"));
        assertEquals("ta-ble", split(en, "table"));
        assertEquals("project", split(en, "project"));
    }

    // ---- language lookup -------------------------------------------------------------------------

    @Test
    public void languageTagsResolveByPrimarySubtag() {
        assertSame(bundled("de"), bundled("de-CH"));
        assertSame(bundled("de"), bundled("de_DE"));
        assertSame(bundled("en"), bundled("EN-us"));
        assertSame(bundled("es"), bundled(" es-419 "));
        assertEquals(new TreeSet<>(Arrays.asList("de", "en", "es")), HyphenationDictionary.bundledLanguages());
    }

    @Test
    public void languageWithoutDictionaryIsAbsentNotAnException() {
        assertFalse(HyphenationDictionary.forLanguage("fr").isPresent());
        assertFalse(HyphenationDictionary.forLanguage("it-CH").isPresent());
        assertFalse(HyphenationDictionary.forLanguage("").isPresent());
        assertFalse(HyphenationDictionary.forLanguage("-").isPresent());
        assertFalse(HyphenationDictionary.forLanguage(null).isPresent());
    }

    @Test
    public void emptyOrNullWordHasNoBreaks() {
        assertArrayEquals(new int[0], bundled("en").breakPoints("", 2, 2));
        assertArrayEquals(new int[0], bundled("en").breakPoints(null, 2, 2));
    }

    // ---- formats ---------------------------------------------------------------------------------

    @Test
    public void readsTexPatternsWithCommentsAndExceptions() throws IOException {
        HyphenationDictionary dictionary = read(
            "% header, no charset line\n"
                + "\\patterns{% a comment right after the brace\n"
                + "b1a b1c % trailing comment x1x\n"
                + "}\n"
                + "\\hyphenation{%\n"
                + "ab-ca-ba\n"
                + "}\n", "UTF-8");
        assertEquals("ab-ab-ab", split(dictionary, "ababab", 1, 1));
        assertEquals("ab-ca-ba", split(dictionary, "abcaba", 1, 1)); // exception, not patterns
        assertEquals("AB-CA-BA", split(dictionary, "ABCABA", 1, 1));
        assertEquals("abxxab", split(dictionary, "abxxab", 1, 1)); // "x1x" was a comment
    }

    @Test
    public void readsLibHnjInItsDeclaredCharset() throws IOException {
        HyphenationDictionary dictionary = read(
            "ISO8859-1\nLEFTHYPHENMIN 2\n% comment\nä1b\nno/n=o,1,1\n", "ISO-8859-1");
        assertEquals("aä-ba", split(dictionary, "aäba", 1, 1));
    }

    @Test
    public void twoLevelLibHnjIsRefusedNotMerged() {
        assertUnreadable("UTF-8\n1-1\nNEXTLEVEL\nb1a\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void brokenContentIsAnIOExceptionAndLoadsAsAbsent() {
        assertUnreadable(new byte[0]);
        assertUnreadable("not-a-charset??\n1ba\n".getBytes(StandardCharsets.US_ASCII));
        assertUnreadable("\\patterns{1ba 1ca\n".getBytes(StandardCharsets.UTF_8));
        assertUnreadable("\\patterns{}\n".getBytes(StandardCharsets.UTF_8));
        assertUnreadable(new byte[] {'\\', 'p', 'a', 't', 't', 'e', 'r', 'n', 's', '{', '1', (byte) 0xC3, '}'});
        assertUnreadable("UTF-8\n% only comments\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void failingStreamDoesNotCrashTheCaller() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk on fire");
            }
        };
        try {
            HyphenationDictionary.read(failing);
            fail("expected an IOException");
        } catch (IOException expected) {
            assertEquals("disk on fire", expected.getMessage());
        }
        assertFalse(HyphenationDictionary.load(failing, "failing").isPresent());
        assertFalse(HyphenationDictionary.load(null, "missing").isPresent());
    }
}
