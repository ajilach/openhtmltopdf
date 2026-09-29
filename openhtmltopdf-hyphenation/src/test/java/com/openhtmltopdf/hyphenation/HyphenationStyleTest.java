package com.openhtmltopdf.hyphenation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Selector;
import org.junit.Test;

/**
 * The hyphenation policy comes from the document's stylesheet, and a stylesheet that says nothing
 * about {@code hyphens} gets none — so a document is only hyphenated when its own stylesheet asks.
 *
 * <p>The documents are jsoup documents, walked the way a caller would: every element's own text
 * nodes, hyphenated with that element's {@link Hyphenator}. Assertions use {@code wholeText()},
 * since jsoup's {@code text()} drops U+00AD as invisible.
 */
public class HyphenationStyleTest {

    private static final char SHY = Hyphenator.SOFT_HYPHEN;

    private static final String TEXT = "Die Bundesrepublik Deutschland verwaltet Wertpapiere";

    /** The tree a jsoup caller hands to {@link HyphenationStyle#read}. */
    private static final class JsoupTree implements HyphenationStyle.Tree<Element> {
        private final Document document;

        JsoupTree(Document document) {
            this.document = document;
        }

        @Override
        public Iterable<Element> select(String selector) {
            try {
                return document.select(selector);
            } catch (Selector.SelectorParseException e) {
                return Collections.emptyList();
            }
        }

        @Override
        public Iterable<Element> children(Element element) {
            return element.children();
        }

        @Override
        public String attribute(Element element, String name) {
            return element.hasAttr(name) ? element.attr(name) : null;
        }
    }

    private static void apply(Document document) {
        List<String> stylesheets = new ArrayList<>();
        for (Element style : document.select("style")) {
            stylesheets.add(style.data());
        }
        HyphenationStyle<Element> style =
            HyphenationStyle.read(stylesheets, document.selectFirst("html"), new JsoupTree(document));
        if (style.isEmpty()) {
            return;
        }
        for (Element element : document.body().select("*")) {
            Hyphenator hyphenator = style.hyphenatorFor(element);
            if (hyphenator == null) {
                continue;
            }
            for (Node child : element.childNodes()) {
                if (child instanceof TextNode) {
                    TextNode text = (TextNode) child;
                    text.text(hyphenator.hyphenate(text.getWholeText()));
                }
            }
        }
    }

    private static Document render(String language, String css, String body) {
        Document document = Jsoup.parse("<html lang=\"" + language + "\"><head><style>" + css
            + "</style></head><body>" + body + "</body></html>");
        apply(document);
        return document;
    }

    private static String p(String language, String css, String body) {
        return render(language, css, body).selectFirst("p").wholeText();
    }

    // ---- hyphens and hyphenate-limit-chars -------------------------------------------------------

    @Test
    public void aStylesheetThatSaysNothingGetsNoHyphenation() {
        assertEquals(TEXT, p("de", "body { font-size: 8pt; text-align: justify; }", "<p>" + TEXT + "</p>"));
    }

    @Test
    public void noStylesheetAtAllGetsNoHyphenation() {
        Document document = Jsoup.parse("<html lang=\"de\"><body><p>" + TEXT + "</p></body></html>");
        apply(document);
        assertEquals(TEXT, document.selectFirst("p").wholeText());
    }

    @Test
    public void hyphensAutoOnBodyReachesEveryDescendant() {
        Document document = render("de", "body { hyphens: auto; }", "<div><p><span>Deutschland</span></p></div>");
        assertEquals("Deutsch" + SHY + "land", document.selectFirst("span").wholeText());
    }

    @Test
    public void hyphensManualSwitchesASubtreeBackOff() {
        Document document = render("de",
            "body { hyphens: auto; } h1, .footer { hyphens: manual; }",
            "<h1>Deutschland</h1><div class=\"footer\"><p>Deutschland</p></div><p>Deutschland</p>");
        assertEquals("Deutschland", document.selectFirst("h1").wholeText());
        assertEquals("Deutschland", document.selectFirst(".footer p").wholeText());
        assertEquals("Deutsch" + SHY + "land", document.select("body > p").first().wholeText());
    }

    @Test
    public void hyphensNoneIsTreatedAsManual() {
        assertEquals("Deutschland", p("de", "body { hyphens: auto; } p { hyphens: none; }", "<p>Deutschland</p>"));
    }

    @Test
    public void theWordLimitComesFromHyphenateLimitChars() {
        // "Bedarf" is 6 letters: hyphenated at 6, not at 7.
        assertEquals("Be" + SHY + "darf", p("de", "body { hyphens: auto; hyphenate-limit-chars: 6 2 2; }", "<p>Bedarf</p>"));
        assertEquals("Bedarf", p("de", "body { hyphens: auto; hyphenate-limit-chars: 7 2 2; }", "<p>Bedarf</p>"));
    }

    @Test
    public void theEdgeLimitsComeFromHyphenateLimitChars() {
        // Before 3 rules out "Be-darf"; with one value given, before and after are auto (2 2).
        assertEquals("Bedarf", p("de", "body { hyphens: auto; hyphenate-limit-chars: 6 3; }", "<p>Bedarf</p>"));
        assertEquals("Be" + SHY + "darf", p("de", "body { hyphens: auto; hyphenate-limit-chars: 6; }", "<p>Bedarf</p>"));
    }

    @Test
    public void autoLimitsAreFiveTwoTwo() {
        // A 5-letter word is hyphenated by default and not with a minimum of 6.
        String css = "body { hyphens: auto; }";
        assertTrue(p("de", css, "<p>Kunde</p>").indexOf(SHY) > 0);
        assertEquals("Kunde", p("de", css + " p { hyphenate-limit-chars: 6 2 2; }", "<p>Kunde</p>"));
    }

    @Test
    public void theLaterRuleWinsAndAStyleAttributeWinsOverBoth() {
        assertEquals("Deutschland", p("de", "p { hyphens: auto; } p { hyphens: manual; }", "<p>Deutschland</p>"));
        assertEquals("Deutsch" + SHY + "land",
            p("de", "p { hyphens: manual; }", "<p style=\"hyphens: auto\">Deutschland</p>"));
    }

    @Test
    public void anInvalidDeclarationDoesNotOverrideAValidOne() {
        assertEquals("Deutsch" + SHY + "land",
            p("de", "p { hyphens: auto; } p { hyphens: sometimes; hyphenate-limit-chars: x; }", "<p>Deutschland</p>"));
    }

    @Test
    public void atRulesCommentsStringsAndDataUrisAreNotMistakenForRules() {
        String css = "@font-face { font-family: X; src: url(data:font/ttf;base64,AAAA); }\n"
            + "@media print { p { hyphens: auto; } }\n"
            + "/* p { hyphens: auto; } */\n"
            + ".logo { background: url(data:image/png;base64,iVBOR) no-repeat; content: \"{ hyphens: auto }\"; }\n"
            + "p::after { hyphens: auto; }\n";
        assertEquals("Deutschland", p("de", css, "<p>Deutschland</p>"));
        // ...and a rule after all of them is still found.
        assertEquals("Deutsch" + SHY + "land", p("de", css + "p { hyphens: auto; }", "<p>Deutschland</p>"));
    }

    @Test
    public void aLanguageWithoutPatternsIsLeftAlone() {
        String css = "body { hyphens: auto; }";
        assertEquals("Deutschland", p("fr", css, "<p>Deutschland</p>"));
        Document noLanguage = Jsoup.parse("<html><head><style>" + css + "</style></head><body><p>Deutschland</p></body></html>");
        apply(noLanguage);
        assertEquals("Deutschland", noLanguage.selectFirst("p").wholeText());
    }

    @Test
    public void aWordThatAlreadyHasAHyphenOrADigitIsLeftWhole() {
        assertEquals("SEPA-Firmenlastschrift AAAR019Wertpapiere",
            p("de", "body { hyphens: auto; }", "<p>SEPA-Firmenlastschrift AAAR019Wertpapiere</p>"));
    }

    @Test
    public void aTenantStylesheetHyphenatesTheBodyTextOnly() {
        // The shape of a real tenant stylesheet: on for the body, off for the title, the footnotes
        // and the running header and footer.
        String css = "body { margin: 0; font-family: Frutiger; }\n"
            + "body { hyphens: auto; hyphenate-limit-chars: 6 2 2; }\n"
            + "h1,\n.footnote,\n.first-header,\n.header,\n.footer {\n    hyphens: manual;\n}\n";
        Document document = render("de", css,
            "<div class=\"first-header\"><p>Deutschland</p></div>"
                + "<div class=\"header\"><p>Deutschland</p></div>"
                + "<div class=\"footer\"><p>Deutschland</p></div>"
                + "<div class=\"body\"><div class=\"container\">"
                + "<h1>Deutschland</h1><h4>Deutschland</h4><p class=\"text\">Deutschland Kunde</p></div>"
                + "<div class=\"footnote\"><p>Deutschland</p></div></div>");
        assertEquals("Deutsch" + SHY + "land Kunde", document.selectFirst("p.text").wholeText());
        assertEquals("Deutsch" + SHY + "land", document.selectFirst("h4").wholeText());
        assertEquals("Deutschland", document.selectFirst("h1").wholeText());
        assertEquals("Deutschland", document.selectFirst(".footnote p").wholeText());
        assertEquals("Deutschland", document.selectFirst(".first-header p").wholeText());
        assertEquals("Deutschland", document.selectFirst(".header p").wholeText());
        assertEquals("Deutschland", document.selectFirst(".footer p").wholeText());
        assertFalse(document.selectFirst("style").data().indexOf(SHY) >= 0);
    }

    // ---- --hyphenate-exceptions ----------------------------------------------------------------

    @Test
    public void anExceptionWordBreaksOnlyAtItsListedPoints() {
        String on = "body { hyphens: auto; hyphenate-limit-chars: 6 2 2; }";
        // The German patterns split the English name where no English reader would.
        assertEquals("Cle" + SHY + "arstream", p("de", on, "<p>Clearstream</p>"));
        assertEquals("Clear" + SHY + "stream",
            p("de", on + " body { --hyphenate-exceptions: \"Clear-stream\" \"Swit-zer-land\"; }", "<p>Clearstream</p>"));
        assertEquals("Swit" + SHY + "zer" + SHY + "land",
            p("de", "body { hyphens: auto; --hyphenate-exceptions: \"Clear-stream\" \"Swit-zer-land\"; }", "<p>Switzerland</p>"));
    }

    @Test
    public void anExceptionWithoutAHyphenIsNeverBroken() {
        assertEquals("Deutschland", p("de", "body { hyphens: auto; --hyphenate-exceptions: Deutschland; }", "<p>Deutschland</p>"));
    }

    @Test
    public void exceptionsMatchTheWholeWordInAnyCase() {
        String css = "body { hyphens: auto; --hyphenate-exceptions: \"clear-stream\"; }";
        assertEquals("CLEAR" + SHY + "STREAM", p("de", css, "<p>CLEARSTREAM</p>"));
        // A longer word is another word: it gets the patterns.
        assertEquals("Cle" + SHY + "arstreams", p("de", css, "<p>Clearstreams</p>"));
    }

    @Test
    public void theLimitsStillApplyToAnException() {
        // "Jail-break" is allowed at 2 2; with at least 5 before a break it is not.
        assertEquals("Jail" + SHY + "break",
            p("de", "body { hyphens: auto; --hyphenate-exceptions: \"Jail-break\"; }", "<p>Jailbreak</p>"));
        assertEquals("Jailbreak",
            p("de", "body { hyphens: auto; hyphenate-limit-chars: 6 5 2; --hyphenate-exceptions: \"Jail-break\"; }", "<p>Jailbreak</p>"));
        // Below the word limit nothing breaks, listed or not.
        assertEquals("Jailbreak",
            p("de", "body { hyphens: auto; hyphenate-limit-chars: 10; --hyphenate-exceptions: \"Jail-break\"; }", "<p>Jailbreak</p>"));
    }

    @Test
    public void exceptionsInheritAndALaterDeclarationReplacesTheList() {
        String css = "body { hyphens: auto; --hyphenate-exceptions: \"Clear-stream\"; }"
            + " .other { --hyphenate-exceptions: \"Jail-break\"; } .off { --hyphenate-exceptions: none; }";
        Document document = render("de", css,
            "<div><p id=\"a\">Clearstream</p></div><div class=\"other\"><p id=\"b\">Clearstream Jailbreak</p></div>"
                + "<div class=\"off\"><p id=\"c\">Clearstream</p></div>");
        assertEquals("Clear" + SHY + "stream", document.getElementById("a").wholeText());
        assertEquals("Cle" + SHY + "arstream Jail" + SHY + "break", document.getElementById("b").wholeText());
        assertEquals("Cle" + SHY + "arstream", document.getElementById("c").wholeText());
    }

    @Test
    public void anInvalidExceptionListIsDroppedWhole() {
        String css = "body { hyphens: auto; --hyphenate-exceptions: \"Clear-stream\"; }"
            + " p { --hyphenate-exceptions: \"Jail--break\" \"Clear-stream\"; }"
            + " p { --hyphenate-exceptions: \"Clear-stream2\"; } p { --hyphenate-exceptions: -Clear; }";
        assertEquals("Clear" + SHY + "stream", p("de", css, "<p>Clearstream</p>"));
    }

    @Test
    public void aCustomPropertyNameIsCaseSensitive() {
        assertEquals("Cle" + SHY + "arstream",
            p("de", "body { hyphens: auto; --Hyphenate-Exceptions: \"Clear-stream\"; }", "<p>Clearstream</p>"));
    }

    @Test
    public void exceptionsWithoutHyphensAutoDoNothing() {
        assertEquals("Clearstream", p("de", "body { --hyphenate-exceptions: \"Clear-stream\"; }", "<p>Clearstream</p>"));
    }

    // ---- lang per element ----------------------------------------------------------------------

    @Test
    public void thePatternsFollowTheNearestLangAttribute() {
        String css = "body { hyphens: auto; }";
        Document document = render("de", css,
            "<p id=\"de\">Clearstream</p><p id=\"en\" lang=\"en\">Clearstream</p>"
                + "<div lang=\"en-GB\"><p id=\"nested\">Clearstream</p></div>");
        assertEquals("Cle" + SHY + "arstream", document.getElementById("de").wholeText());
        HyphenationDictionary english = HyphenationDictionary.forLanguage("en").get();
        String expected = new Hyphenator(english, 5, 2, 2, null).hyphenate("Clearstream");
        assertEquals(expected, document.getElementById("en").wholeText());
        assertEquals(expected, document.getElementById("nested").wholeText());
        assertFalse(expected.equals(document.getElementById("de").wholeText()));
    }

    @Test
    public void aPassageInALanguageWithoutPatternsIsNotHyphenated() {
        Document document = render("de", "body { hyphens: auto; }",
            "<p id=\"fr\" lang=\"fr\">Deutschland</p><p id=\"unknown\" lang=\"\">Deutschland</p>"
                + "<div lang=\"fr\"><p id=\"back\" lang=\"de\">Deutschland</p></div>");
        assertEquals("Deutschland", document.getElementById("fr").wholeText());
        assertEquals("Deutschland", document.getElementById("unknown").wholeText());
        assertEquals("Deutsch" + SHY + "land", document.getElementById("back").wholeText());
    }

    // ---- the API itself ------------------------------------------------------------------------

    @Test
    public void theHyphenatorCarriesTheResolvedLimits() {
        Document document = Jsoup.parse("<html lang=\"de\"><head><style>body { hyphens: auto; hyphenate-limit-chars: 6 3; }"
            + " h1 { hyphens: manual; }</style></head><body><h1>x</h1><p>x</p></body></html>");
        HyphenationStyle<Element> style = HyphenationStyle.read(
            Collections.singletonList(document.selectFirst("style").data()), document.selectFirst("html"),
            new JsoupTree(document));
        assertFalse(style.isEmpty());
        assertNull(style.hyphenatorFor(document.selectFirst("h1")));
        Hyphenator hyphenator = style.hyphenatorFor(document.selectFirst("p"));
        assertNotNull(hyphenator);
        assertEquals(6, hyphenator.minWordLength());
        assertEquals(3, hyphenator.minBefore());
        assertEquals(3, hyphenator.minAfter());
    }

    @Test
    public void nothingToReadIsEmptyNotAnError() {
        Document document = Jsoup.parse("<html lang=\"de\"><body><p>x</p></body></html>");
        assertTrue(HyphenationStyle.read(null, document.selectFirst("html"), new JsoupTree(document)).isEmpty());
        assertTrue(HyphenationStyle.read(Collections.singletonList("p { hyphens: auto; }"), null,
            new JsoupTree(document)).isEmpty());
        HyphenationDictionary german = HyphenationDictionary.forLanguage("de").get();
        assertEquals("", new Hyphenator(german, 5, 2, 2, null).hyphenate(""));
        assertNull(new Hyphenator(german, 5, 2, 2, null).hyphenate(null));
    }
}
