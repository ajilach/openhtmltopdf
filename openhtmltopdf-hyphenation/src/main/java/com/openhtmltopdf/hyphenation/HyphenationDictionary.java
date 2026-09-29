package com.openhtmltopdf.hyphenation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Liang's hyphenation algorithm: the positions inside a word where a line may be broken.
 *
 * <p>The patterns are competing rules: every substring of {@code .word.} that a pattern matches
 * contributes a digit between each pair of letters, the highest digit at a position wins, and an
 * odd digit means a break is allowed there. A dictionary is a few thousand of them, so one word
 * costs a handful of map lookups and nothing is held per document. An instance is immutable and
 * safe to share between threads.
 *
 * <p>This class only answers <em>where</em> a word may break. <em>Whether</em> to hyphenate — which
 * elements, the minimum word length, the characters kept whole at either end — is the caller's
 * policy (in CSS terms {@code hyphens} and {@code hyphenate-limit-chars}), not this module's.
 *
 * <p><b>Two input formats</b> are read, detected from the content:
 * <ul>
 *   <li><b>hyph-utf8 TeX</b> ({@code hyph-*.tex}): UTF-8, the patterns inside
 *       {@code \patterns{…}}, optional exceptions inside {@code \hyphenation{…}}, {@code %}
 *       comments. There is no charset line.</li>
 *   <li><b>LibHnj</b> ({@code hyph_*.dic}, the LibreOffice/Hunspell format): the first line names
 *       the charset of the rest, then one pattern per line. Only single-level files are accepted.
 *       A two-level file (a {@code NEXTLEVEL} line, compound patterns first) is refused rather than
 *       merged into one map — merging the levels produces wrong breaks such as
 *       {@code Deut-sch-land}.</li>
 * </ul>
 *
 * <p>Deliberately not implemented: <b>non-standard hyphenation</b>, the LibHnj
 * {@code pattern/change,i,c} form that spells a word differently across the break. Those lines are
 * skipped, so the word simply gets no break point there. None of the bundled files has one.
 *
 * <p><b>Bundled languages</b> ({@link #forLanguage(String)}): {@code de} (hyph-utf8
 * {@code hyph-de-1996}, MIT), {@code es} (hyph-utf8 {@code hyph-es}, MIT) and {@code en}
 * (LibreOffice {@code hyph_en_US.dic}, Knuth's {@code hyphen.tex} plus the TUGboat exceptions).
 * Their notices are in {@code META-INF/THIRD-PARTY-NOTICES.txt}.
 */
public final class HyphenationDictionary {

    private static final Logger LOG = Logger.getLogger(HyphenationDictionary.class.getName());

    /** Primary language subtag to the bundled resource, next to this class. */
    private static final Map<String, String> BUNDLED;

    static {
        Map<String, String> bundled = new HashMap<>();
        bundled.put("de", "hyph-de-1996.tex");
        bundled.put("en", "hyph_en_US.dic");
        bundled.put("es", "hyph-es.tex");
        BUNDLED = Collections.unmodifiableMap(bundled);
    }

    /** One entry per resource, including the failures, so a broken file is read (and logged) once. */
    private static final Map<String, Optional<HyphenationDictionary>> LOADED = new ConcurrentHashMap<>();

    /**
     * LibHnj directives and comments, not patterns. Matches the set the reference implementation
     * (Pyphen) ignores.
     */
    private static final String[] LIBHNJ_IGNORED = {
        "%", "#", "LEFTHYPHENMIN", "RIGHTHYPHENMIN", "COMPOUNDLEFTHYPHENMIN", "COMPOUNDRIGHTHYPHENMIN"
    };

    private static final byte[] TEX_PATTERNS = "\\patterns{".getBytes(StandardCharsets.US_ASCII);

    /** Pattern letters to the values around them: {@code values[i]} sits BEFORE {@code letters[i]}. */
    private static final class Pattern {
        private final int offset;
        private final byte[] values;

        private Pattern(int offset, byte[] values) {
            this.offset = offset;
            this.values = values;
        }
    }

    private final Map<String, Pattern> patterns;
    private final Map<String, int[]> exceptions;
    private final int longestPattern;

    private HyphenationDictionary(Map<String, Pattern> patterns, Map<String, int[]> exceptions) {
        this.patterns = patterns;
        this.exceptions = exceptions;
        int longest = 1;
        for (String key : patterns.keySet()) {
            longest = Math.max(longest, key.length());
        }
        this.longestPattern = longest;
    }

    /**
     * The bundled dictionary for a language, by its primary subtag: {@code "de"}, {@code "de-CH"}
     * and {@code "de_DE"} all give the German patterns. Empty for {@code null}, an empty tag, a
     * language without bundled patterns, and a bundled file that cannot be read — never an
     * exception, because a page is correct without hyphenation, only looser, and a dictionary for
     * the wrong language does not degrade gracefully, it breaks words in the wrong places.
     */
    public static Optional<HyphenationDictionary> forLanguage(String languageTag) {
        if (languageTag == null) {
            return Optional.empty();
        }
        String primary = languageTag.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        String resource = BUNDLED.get(primary);
        if (resource == null) {
            return Optional.empty();
        }
        return LOADED.computeIfAbsent(resource,
            name -> load(HyphenationDictionary.class.getResourceAsStream(name), name));
    }

    /** The primary language subtags {@link #forLanguage(String)} has patterns for. */
    public static Set<String> bundledLanguages() {
        return Collections.unmodifiableSet(new TreeSet<>(BUNDLED.keySet()));
    }

    /**
     * Reads a stream and closes it; empty (and logged) if it is missing or cannot be read.
     */
    static Optional<HyphenationDictionary> load(InputStream stream, String name) {
        if (stream == null) {
            LOG.warning("Hyphenation patterns not found: " + name);
            return Optional.empty();
        }
        try (InputStream in = stream) {
            return Optional.of(read(in));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Hyphenation patterns unreadable, hyphenation is off: " + name, e);
            return Optional.empty();
        }
    }

    /**
     * Reads a pattern file in either format (see the class description). The stream is read to
     * its end and not closed. Any content that is not a usable dictionary — an unknown charset,
     * malformed UTF-8, an unclosed {@code \patterns{}}, a two-level LibHnj file, no patterns at
     * all — is an {@link IOException}, never an unchecked exception.
     */
    public static HyphenationDictionary read(InputStream stream) throws IOException {
        byte[] content = readAll(stream);
        Map<String, Pattern> parsed = new HashMap<>();
        Map<String, int[]> exceptions = new HashMap<>();
        if (indexOf(content, TEX_PATTERNS) >= 0) {
            readTex(decodeStrict(content, 0, StandardCharsets.UTF_8), parsed, exceptions);
        } else {
            readLibHnj(content, parsed);
        }
        if (parsed.isEmpty()) {
            throw new IOException("no hyphenation patterns in dictionary");
        }
        return new HyphenationDictionary(parsed, exceptions);
    }

    /**
     * Positions inside {@code word} where a break is allowed, counted as "after this many
     * characters", ascending, with the first {@code leftMin} and last {@code rightMin} characters
     * kept whole. Case does not matter. A word whose lower-case form has a different length gets
     * none, since every position would shift.
     */
    public int[] breakPoints(String word, int leftMin, int rightMin) {
        if (word == null || word.isEmpty()) {
            return new int[0];
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.length() != word.length()) {
            return new int[0];
        }
        int last = word.length() - rightMin;

        int[] exception = exceptions.get(lower);
        if (exception != null) {
            int[] found = new int[exception.length];
            int count = 0;
            for (int position : exception) {
                if (position >= leftMin && position <= last) {
                    found[count++] = position;
                }
            }
            return trim(found, count);
        }

        String anchored = "." + lower + ".";
        byte[] references = new byte[anchored.length() + 1];
        for (int i = 0; i < anchored.length() - 1; i++) {
            int stop = Math.min(i + longestPattern, anchored.length());
            for (int j = i + 1; j <= stop; j++) {
                Pattern pattern = patterns.get(anchored.substring(i, j));
                if (pattern == null) {
                    continue;
                }
                for (int k = 0; k < pattern.values.length; k++) {
                    int at = i + pattern.offset + k;
                    if (at < references.length && pattern.values[k] > references[at]) {
                        references[at] = pattern.values[k];
                    }
                }
            }
        }

        int[] found = new int[references.length];
        int count = 0;
        for (int i = 0; i < references.length; i++) {
            int position = i - 1;
            if ((references[i] & 1) == 1 && position >= leftMin && position <= last) {
                found[count++] = position;
            }
        }
        return trim(found, count);
    }

    // ---- hyph-utf8 TeX -------------------------------------------------------------------------

    private static void readTex(String text, Map<String, Pattern> patterns, Map<String, int[]> exceptions)
            throws IOException {
        String uncommented = stripTexComments(text);
        String patternBlock = texGroup(uncommented, "\\patterns{");
        if (patternBlock == null) {
            throw new IOException("no \\patterns{} group in TeX pattern file");
        }
        for (String token : patternBlock.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                parsePattern(token, patterns);
            }
        }
        String exceptionBlock = texGroup(uncommented, "\\hyphenation{");
        if (exceptionBlock != null) {
            for (String token : exceptionBlock.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    parseException(token, exceptions);
                }
            }
        }
    }

    private static String stripTexComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\r\n|\r|\n", -1)) {
            int comment = line.indexOf('%');
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    /** The content of {@code opening…}, or null if there is no such group; unclosed is an error. */
    private static String texGroup(String text, String opening) throws IOException {
        int start = text.indexOf(opening);
        if (start < 0) {
            return null;
        }
        int from = start + opening.length();
        int end = text.indexOf('}', from);
        if (end < 0) {
            throw new IOException("unclosed " + opening + "} group in TeX pattern file");
        }
        return text.substring(from, end);
    }

    /** {@code ta-ble}: a break after every hyphen, in the lower-case word without them. */
    private static void parseException(String token, Map<String, int[]> into) {
        StringBuilder letters = new StringBuilder(token.length());
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '-') {
                positions.add(letters.length());
            } else {
                letters.append(c);
            }
        }
        int[] values = new int[positions.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = positions.get(i);
        }
        into.put(letters.toString().toLowerCase(Locale.ROOT), values);
    }

    // ---- LibHnj ---------------------------------------------------------------------------------

    /**
     * The first line is the charset name the rest of the file is in — the German LibreOffice
     * patterns are ISO 8859-1, and reading them as UTF-8 silently corrupts every pattern
     * containing an umlaut.
     */
    private static void readLibHnj(byte[] content, Map<String, Pattern> patterns) throws IOException {
        int newline = indexOfNewline(content);
        String charsetName = new String(content, 0, newline, StandardCharsets.US_ASCII).trim();
        if ("microsoft-cp1251".equalsIgnoreCase(charsetName)) {
            charsetName = "cp1251";
        }
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a hyphenation dictionary: unknown charset line '" + charsetName + "'", e);
        }
        String body = decodeStrict(content, Math.min(newline + 1, content.length), charset);
        for (String line : body.split("\r\n|\r|\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("NEXTLEVEL")) {
                throw new IOException("two-level (NEXTLEVEL) LibHnj dictionaries are not supported");
            }
            if (trimmed.isEmpty() || isLibHnjIgnored(trimmed)) {
                continue;
            }
            String pattern = expandHexEscapes(trimmed);
            if (pattern.indexOf('/') >= 0 && pattern.indexOf('=') >= 0) {
                continue; // non-standard hyphenation, see the class description
            }
            parsePattern(pattern, patterns);
        }
    }

    private static boolean isLibHnjIgnored(String line) {
        for (String prefix : LIBHNJ_IGNORED) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ^^hh} is the format's escape for a byte that the file's charset cannot spell. */
    private static String expandHexEscapes(String pattern) {
        if (pattern.indexOf("^^") < 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length());
        int i = 0;
        while (i < pattern.length()) {
            if (i + 3 < pattern.length() && pattern.charAt(i) == '^' && pattern.charAt(i + 1) == '^'
                && isHex(pattern.charAt(i + 2)) && isHex(pattern.charAt(i + 3))) {
                out.append((char) Integer.parseInt(pattern.substring(i + 2, i + 4), 16));
                i += 4;
            } else {
                out.append(pattern.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    // ---- shared ---------------------------------------------------------------------------------

    /** {@code 1ba2r}: letters, with the digit (0 if absent) that sits before each and after the last. */
    private static void parsePattern(String pattern, Map<String, Pattern> into) {
        StringBuilder letters = new StringBuilder(pattern.length());
        List<Byte> values = new ArrayList<>(pattern.length() + 1);
        int i = 0;
        while (i <= pattern.length()) {
            byte value = 0;
            if (i < pattern.length() && isDigit(pattern.charAt(i))) {
                value = (byte) (pattern.charAt(i) - '0');
                i++;
            }
            boolean hasLetter = i < pattern.length();
            if (hasLetter) {
                letters.append(pattern.charAt(i));
                i++;
            }
            values.add(value);
            if (!hasLetter) {
                break;
            }
        }

        int start = 0;
        int end = values.size();
        while (start < end && values.get(start) == 0) {
            start++;
        }
        if (start == end) {
            return; // nothing but zeros: the pattern allows no break anywhere
        }
        while (values.get(end - 1) == 0) {
            end--;
        }
        byte[] trimmed = new byte[end - start];
        for (int k = 0; k < trimmed.length; k++) {
            trimmed[k] = values.get(start + k);
        }
        into.put(letters.toString(), new Pattern(start, trimmed));
    }

    private static String decodeStrict(byte[] content, int from, Charset charset) throws IOException {
        try {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content, from, content.length - from))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("hyphenation patterns are not valid " + charset.name(), e);
        }
    }

    private static int[] trim(int[] values, int count) {
        int[] result = new int[count];
        System.arraycopy(values, 0, result, 0, count);
        return result;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static int indexOfNewline(byte[] content) {
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                return i;
            }
        }
        return content.length;
    }

    private static int indexOf(byte[] content, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= content.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (content[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
