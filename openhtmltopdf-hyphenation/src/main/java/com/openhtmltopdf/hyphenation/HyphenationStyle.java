package com.openhtmltopdf.hyphenation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The hyphenation policy a document's own stylesheet declares, resolved for every element of the
 * document: whether its text is hyphenated, in which language, and with which limits. The
 * renderer implements none of the properties read here, so a caller hyphenates the text itself
 * before layout, with the {@link Hyphenator} this class gives per element.
 *
 * <p>This class knows no DOM library. The caller describes its document through {@link Tree}
 * (children, attributes, selector matching), so the same policy reading serves a jsoup document,
 * a W3C DOM or anything else that can evaluate a CSS selector.
 *
 * <p><b>What is read</b>, all inherited, as in CSS:
 * <ul>
 *   <li><a href="https://www.w3.org/TR/css-text-4/#hyphenation">{@code hyphens}</a>: {@code auto}
 *       turns hyphenation on for an element and its descendants, {@code manual} and {@code none}
 *       turn it off. {@code none} is treated as {@code manual}: no soft hyphen is added, and one
 *       already in the content is left as authored.</li>
 *   <li>{@code hyphenate-limit-chars: <word> [<before> [<after>]]}, each an integer or
 *       {@code auto}: the shortest word that is hyphenated and the fewest characters kept before
 *       and after a break. A missing {@code <after>} equals {@code <before>}, a missing
 *       {@code <before>} is {@code auto}, and {@code auto} is {@code 5 2 2}, the values the
 *       specification recommends.</li>
 *   <li>{@value #EXCEPTIONS}, a custom property: a list of words, each a CSS string or a bare
 *       word, with a hyphen at every point the word may break, for instance
 *       {@code --hyphenate-exceptions: "Clear-stream" "Jail-break" "UBS";}. A listed word breaks
 *       only there — or nowhere, when it is listed without a hyphen — whatever the patterns say.
 *       Matching is case-insensitive on the whole word. {@code none} is the empty list. A
 *       declaration overrides the inherited list, it does not add to it. A renderer that does not
 *       know the property ignores it, as CSS requires of custom properties.</li>
 *   <li>The element's language: the nearest {@code lang} attribute on the element or an
 *       ancestor, as in HTML, chooses the {@link HyphenationDictionary}. So a passage marked
 *       {@code lang="en"} inside a German document is hyphenated with English patterns, and a
 *       passage in a language without bundled patterns, or with {@code lang=""}, is not
 *       hyphenated at all.</li>
 * </ul>
 *
 * <p><b>The default is no hyphenation.</b> {@code hyphens} starts as {@code manual}, so a
 * stylesheet that does not mention it gets no soft hyphen anywhere; this class carries no policy
 * values of its own.
 *
 * <p><b>Deliberately a subset of CSS</b>, because the stylesheets it serves are simple:
 * <ul>
 *   <li>only the stylesheets the caller passes and {@code style} attributes are read;</li>
 *   <li>rules inside an at-rule ({@code @media}, {@code @page}, …) are ignored;</li>
 *   <li>when several rules set the same property on the same element, the <b>later one in the
 *       source wins</b>; specificity and {@code !important} are not weighed. A stylesheet that
 *       needs them for these properties is out of scope — write the exception as a later rule;</li>
 *   <li>a selector the {@link Tree} cannot evaluate (a pseudo-element, for instance) selects
 *       nothing, since it cannot select text for hyphenation anyway;</li>
 *   <li>an invalid declaration is dropped, as in CSS, so it cannot override a valid earlier one.</li>
 * </ul>
 *
 * @param <E> the caller's element type.
 */
public final class HyphenationStyle<E> {

    /** The standard property that turns hyphenation on ({@code auto}) or off. */
    public static final String HYPHENS = "hyphens";

    /** The standard property with the word and edge limits. */
    public static final String LIMIT_CHARS = "hyphenate-limit-chars";

    /** The custom property with the per-word exceptions. */
    public static final String EXCEPTIONS = "--hyphenate-exceptions";

    private static final int AUTO_WORD = 5;
    private static final int AUTO_EDGE = 2;

    /**
     * The caller's document, as far as this class needs it. Elements are compared by identity.
     *
     * @param <E> the caller's element type.
     */
    public interface Tree<E> {

        /**
         * Every element of the document the CSS selector (possibly a group, {@code "h1, .note"})
         * matches, in any order. A selector the tree cannot evaluate selects nothing: return an
         * empty result, do not throw.
         */
        Iterable<E> select(String selector);

        /** The element children of an element, in document order. */
        Iterable<E> children(E element);

        /** The value of an attribute ({@code style}, {@code lang}), or null when it is absent. */
        String attribute(E element, String name);
    }

    private static final class Declaration {
        private final String selector;
        private final String property;
        private final String value;

        private Declaration(String selector, String property, String value) {
            this.selector = selector;
            this.property = property;
            this.value = value;
        }
    }

    /** What an element inherits from its parent. */
    private static final class Computed {
        private final boolean auto;
        private final String language;
        private final String limits;
        private final String exceptions;

        private Computed(boolean auto, String language, String limits, String exceptions) {
            this.auto = auto;
            this.language = language;
            this.limits = limits;
            this.exceptions = exceptions;
        }
    }

    private final Map<E, Hyphenator> hyphenated;

    private HyphenationStyle(Map<E, Hyphenator> hyphenated) {
        this.hyphenated = hyphenated;
    }

    /**
     * Resolves the policy for every element under {@code root}, the document's root element.
     *
     * @param stylesheets the text of the document's stylesheets, in document order (for HTML,
     *        the content of its {@code <style>} elements).
     * @param root the root element; its descendants are reached through {@link Tree#children}.
     * @param tree the caller's document.
     */
    public static <E> HyphenationStyle<E> read(Iterable<String> stylesheets, E root, Tree<E> tree) {
        List<Declaration> declarations = new ArrayList<>();
        if (stylesheets != null) {
            for (String stylesheet : stylesheets) {
                if (stylesheet != null) {
                    parseStylesheet(stylesheet, declarations);
                }
            }
        }
        Map<String, Map<E, String>> declared = new HashMap<>();
        declared.put(HYPHENS, new IdentityHashMap<E, String>());
        declared.put(LIMIT_CHARS, new IdentityHashMap<E, String>());
        declared.put(EXCEPTIONS, new IdentityHashMap<E, String>());
        for (Declaration declaration : declarations) {
            Map<E, String> into = declared.get(declaration.property);
            for (E element : tree.select(declaration.selector)) {
                into.put(element, declaration.value);
            }
        }
        Map<E, Hyphenator> hyphenated = new IdentityHashMap<>();
        if (root == null) {
            return new HyphenationStyle<>(hyphenated);
        }
        // A style attribute comes after every stylesheet rule, so it wins.
        applyStyleAttributes(root, tree, declared);
        if (!declared.get(HYPHENS).isEmpty()) {
            resolve(root, new Computed(false, null, null, null), tree, declared,
                new HashMap<String, Optional<Hyphenator>>(), hyphenated);
        }
        return new HyphenationStyle<>(hyphenated);
    }

    /** True when no element of the document is hyphenated. */
    public boolean isEmpty() {
        return hyphenated.isEmpty();
    }

    /**
     * The hyphenator for the element's own text, or null when its {@code hyphens} is not
     * {@code auto} or there are no patterns for its language.
     */
    public Hyphenator hyphenatorFor(E element) {
        return hyphenated.get(element);
    }

    private static <E> void applyStyleAttributes(E element, Tree<E> tree, Map<String, Map<E, String>> declared) {
        String style = tree.attribute(element, "style");
        if (style != null && !style.isEmpty()) {
            List<Declaration> inline = new ArrayList<>();
            parseDeclarations("", style, inline);
            for (Declaration declaration : inline) {
                declared.get(declaration.property).put(element, declaration.value);
            }
        }
        for (E child : tree.children(element)) {
            applyStyleAttributes(child, tree, declared);
        }
    }

    /** Top-down, so every element inherits what its parent computed. */
    private static <E> void resolve(E element, Computed parent, Tree<E> tree,
                                    Map<String, Map<E, String>> declared,
                                    Map<String, Optional<Hyphenator>> hyphenators,
                                    Map<E, Hyphenator> into) {
        String hyphens = declared.get(HYPHENS).get(element);
        String limits = declared.get(LIMIT_CHARS).get(element);
        String exceptions = declared.get(EXCEPTIONS).get(element);
        String language = tree.attribute(element, "lang");
        Computed own = new Computed(
            hyphens == null ? parent.auto : "auto".equals(hyphens),
            language == null ? parent.language : language,
            limits == null ? parent.limits : limits,
            exceptions == null ? parent.exceptions : exceptions);
        if (own.auto) {
            String key = own.language + '\u0000' + own.limits + '\u0000' + own.exceptions;
            Optional<Hyphenator> hyphenator = hyphenators.get(key);
            if (hyphenator == null) {
                hyphenator = create(own);
                hyphenators.put(key, hyphenator);
            }
            if (hyphenator.isPresent()) {
                into.put(element, hyphenator.get());
            }
        }
        for (E child : tree.children(element)) {
            resolve(child, own, tree, declared, hyphenators, into);
        }
    }

    private static Optional<Hyphenator> create(Computed computed) {
        Optional<HyphenationDictionary> dictionary = HyphenationDictionary.forLanguage(computed.language);
        if (!dictionary.isPresent()) {
            return Optional.empty();
        }
        int word = AUTO_WORD;
        int before = AUTO_EDGE;
        int after = AUTO_EDGE;
        if (computed.limits != null) {
            String[] parts = computed.limits.split("\\s+");
            word = parts[0].equals("auto") ? AUTO_WORD : Integer.parseInt(parts[0]);
            before = parts.length < 2 || parts[1].equals("auto") ? AUTO_EDGE : Integer.parseInt(parts[1]);
            after = parts.length < 3 ? before : parts[2].equals("auto") ? AUTO_EDGE : Integer.parseInt(parts[2]);
        }
        Map<String, int[]> exceptions = computed.exceptions == null
            ? Collections.<String, int[]>emptyMap() : parseExceptions(computed.exceptions);
        return Optional.of(new Hyphenator(dictionary.get(), word, before, after, exceptions));
    }

    /** Already validated by {@link #isValid}: the words, lower-cased, to their break points. */
    private static Map<String, int[]> parseExceptions(String value) {
        Map<String, int[]> exceptions = new HashMap<>();
        for (String entry : exceptionEntries(value)) {
            StringBuilder word = new StringBuilder(entry.length());
            int[] points = new int[entry.length()];
            int count = 0;
            for (int i = 0; i < entry.length(); i++) {
                char c = entry.charAt(i);
                if (c == '-') {
                    points[count++] = word.length();
                } else {
                    word.append(c);
                }
            }
            String lower = word.toString().toLowerCase(Locale.ROOT);
            if (lower.length() == word.length()) {
                int[] trimmed = new int[count];
                System.arraycopy(points, 0, trimmed, 0, count);
                exceptions.put(lower, trimmed);
            }
        }
        return exceptions;
    }

    /**
     * The entries of an exception list: quoted strings or bare words, separated by white space
     * (commas are allowed too). Null when the value is not a valid list; empty for {@code none}.
     */
    private static List<String> exceptionEntries(String value) {
        List<String> entries = new ArrayList<>();
        if (value.equals("none")) {
            return entries;
        }
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            String entry;
            if (c == '"' || c == '\'') {
                int close = value.indexOf(c, i + 1);
                if (close < 0) {
                    return null;
                }
                entry = value.substring(i + 1, close);
                i = close + 1;
            } else {
                int end = i;
                while (end < value.length() && !Character.isWhitespace(value.charAt(end))
                    && value.charAt(end) != ',') {
                    end++;
                }
                entry = value.substring(i, end);
                i = end;
            }
            if (!isExceptionWord(entry)) {
                return null;
            }
            entries.add(entry);
        }
        return entries.isEmpty() ? null : entries;
    }

    /** Letters, with single hyphens between them. */
    private static boolean isExceptionWord(String entry) {
        if (entry.isEmpty() || entry.charAt(0) == '-' || entry.charAt(entry.length() - 1) == '-') {
            return false;
        }
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '-') {
                if (entry.charAt(i - 1) == '-') {
                    return false;
                }
            } else if (!Character.isLetter(c)) {
                return false;
            }
        }
        return true;
    }

    /** An invalid declaration is dropped, as CSS does, so it cannot override a valid earlier one. */
    private static boolean isValid(String property, String value) {
        if (HYPHENS.equals(property)) {
            return value.equals("auto") || value.equals("manual") || value.equals("none");
        }
        if (EXCEPTIONS.equals(property)) {
            return exceptionEntries(value) != null;
        }
        String[] parts = value.split("\\s+");
        if (parts.length > 3) {
            return false;
        }
        for (String part : parts) {
            if (!part.equals("auto") && !part.matches("\\d{1,3}")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects the declarations of the three properties from the top-level style rules, in source
     * order. At-rules are skipped whole, blocks and all.
     */
    private static void parseStylesheet(String css, List<Declaration> into) {
        String text = stripComments(css);
        int i = 0;
        while (i < text.length()) {
            int open = indexOfOutsideStrings(text, i, '{', ';');
            if (open < 0) {
                return;
            }
            String prelude = text.substring(i, open).trim();
            if (text.charAt(open) == ';') { // a statement at-rule such as @import
                i = open + 1;
                continue;
            }
            int close = matchingBrace(text, open);
            if (!prelude.startsWith("@") && !prelude.isEmpty()) {
                parseDeclarations(prelude, text.substring(open + 1, close), into);
            }
            i = close + 1;
        }
    }

    private static void parseDeclarations(String selector, String block, List<Declaration> into) {
        for (String declaration : splitOutsideParentheses(block)) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = declaration.substring(0, colon).trim();
            // A custom property's name is case-sensitive; a standard one's is not.
            String property = name.startsWith("--") ? name : name.toLowerCase(Locale.ROOT);
            if (!HYPHENS.equals(property) && !LIMIT_CHARS.equals(property) && !EXCEPTIONS.equals(property)) {
                continue;
            }
            String value = declaration.substring(colon + 1).trim().replaceAll("\\s*!\\s*important$", "");
            if (!EXCEPTIONS.equals(property)) {
                value = value.toLowerCase(Locale.ROOT);
            }
            if (isValid(property, value)) {
                into.add(new Declaration(selector, property, value));
            }
        }
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    /** The first of the two characters at or after {@code from}, not inside a quoted string. */
    private static int indexOfOutsideStrings(String text, int from, char first, char second) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == first || c == second) {
                return i;
            }
        }
        return -1;
    }

    /** The {@code }} closing the block opened at {@code open}; the end of the text if unclosed. */
    private static int matchingBrace(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return text.length();
    }

    /** Declarations split at {@code ;}, but not inside {@code url(data:…;base64,…)} or a string. */
    private static List<String> splitOutsideParentheses(String block) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ';' && depth == 0) {
                parts.add(block.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(block.substring(start));
        return parts;
    }
}
