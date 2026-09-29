package com.openhtmltopdf.hyphenation;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Puts U+00AD SOFT HYPHEN into text, as the policy in force on one element asks: its language's
 * {@link HyphenationDictionary}, the limits of {@code hyphenate-limit-chars} and the words of
 * {@code --hyphenate-exceptions}. Obtained from {@link HyphenationStyle#hyphenatorFor(Object)};
 * immutable and safe to share.
 *
 * <p>The renderer breaks a line at U+00AD and paints the hyphen only there, and the character
 * does not reach the extracted text. So hyphenating the text before layout is all it takes, and
 * nothing of it has to be stored with the content.
 *
 * <p><b>What counts as a word</b> is a run of letters ({@link Character#isLetter(char)}) that
 * nothing word-like touches. A run next to a digit, a hyphen (U+002D, U+2010, U+2011) or a soft
 * hyphen is left whole: a word that already contains a hyphen has a break point, and in
 * {@code SEPA-Basislastschrift} the line should break after {@code SEPA-} rather than inside the
 * compound; a token with a digit in it, such as a form code, is not a word at all.
 */
public final class Hyphenator {

    /** U+00AD SOFT HYPHEN, the character this class inserts. */
    public static final char SOFT_HYPHEN = '­';

    private final HyphenationDictionary dictionary;
    private final int minWordLength;
    private final int minBefore;
    private final int minAfter;
    private final Map<String, int[]> exceptions;

    Hyphenator(HyphenationDictionary dictionary, int minWordLength, int minBefore, int minAfter,
               Map<String, int[]> exceptions) {
        this.dictionary = dictionary;
        this.minWordLength = minWordLength;
        this.minBefore = minBefore;
        this.minAfter = minAfter;
        this.exceptions = exceptions == null ? Collections.<String, int[]>emptyMap() : exceptions;
    }

    /** The shortest word that is hyphenated (the first value of {@code hyphenate-limit-chars}). */
    public int minWordLength() {
        return minWordLength;
    }

    /** The fewest characters kept before a break (the second value). */
    public int minBefore() {
        return minBefore;
    }

    /** The fewest characters kept after a break (the third value). */
    public int minAfter() {
        return minAfter;
    }

    /**
     * The text with a soft hyphen at every allowed break of every word long enough. A word listed
     * in {@code --hyphenate-exceptions} (compared case-insensitively, as a whole word) breaks only
     * at its listed points; one listed without a hyphen does not break at all. The limits apply to
     * the listed points too.
     */
    public String hyphenate(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + text.length() / 8);
        int i = 0;
        while (i < text.length()) {
            if (!Character.isLetter(text.charAt(i))) {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            int end = i;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            String word = text.substring(i, end);
            boolean partOfLongerToken =
                (i > 0 && isWordCharacter(text.charAt(i - 1)))
                    || (end < text.length() && isWordCharacter(text.charAt(end)));
            if (word.length() >= minWordLength && !partOfLongerToken) {
                out.append(withSoftHyphens(word));
            } else {
                out.append(word);
            }
            i = end;
        }
        return out.toString();
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '‐' || c == '‑'
            || c == SOFT_HYPHEN;
    }

    private String withSoftHyphens(String word) {
        int[] positions = breakPoints(word);
        if (positions.length == 0) {
            return word;
        }
        StringBuilder out = new StringBuilder(word.length() + positions.length);
        int previous = 0;
        for (int position : positions) {
            out.append(word, previous, position).append(SOFT_HYPHEN);
            previous = position;
        }
        return out.append(word, previous, word.length()).toString();
    }

    private int[] breakPoints(String word) {
        int[] listed = exceptions.isEmpty() ? null : exceptions.get(word.toLowerCase(Locale.ROOT));
        if (listed == null) {
            return dictionary.breakPoints(word, minBefore, minAfter);
        }
        int last = word.length() - minAfter;
        int[] found = new int[listed.length];
        int count = 0;
        for (int position : listed) {
            if (position >= minBefore && position <= last) {
                found[count++] = position;
            }
        }
        int[] result = new int[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }
}
