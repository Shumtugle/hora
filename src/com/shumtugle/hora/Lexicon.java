package com.shumtugle.hora;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pronunciation rules, stored as plain text, one rule per line:
 *   word = replacement        whole word, case-insensitive
 *   two words = replacement   phrase, matched as a whole
 *   /pattern/ = replacement   regular expression, groups allowed as $1
 * Lines starting with # are comments. In a replacement, "+" before a letter
 * marks that letter as stressed.
 *
 * The user's own file always wins over imported dictionaries. Single words
 * are looked up in a hash table, so large imported dictionaries stay cheap.
 * The parsed result is cached per process and rebuilt when any file changes.
 */
final class Lexicon {
    private static final String FILE = "lexicon.txt";
    static final String IMPORT_DIR = "lexicons";
    static final char ACUTE = '\u0301';

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern STRESS = Pattern.compile("\\+(\\p{L})");
    private static final String STRESSED = "$1" + ACUTE;
    private static final Pattern SINGLE_WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:-[\\p{L}\\p{M}]+)*");
    private static final Pattern WORD = SINGLE_WORD;

    private static Lexicon cached;
    private static String cachedStamp;
    /** Built-in spelling restoration: plain form to the form with the diacritic letter. */
    private static final String RESTORE_ASSET = "lexicons/yo.txt";
    /** Built-in stress marks for common words, in the rule format. */
    private static final String STRESS_ASSET = "lexicons/stress.txt";
    private static Map<String, String> restore;
    /** Lowest layer: built-in stress marks, consulted when no other rule has the word. */
    private static Map<String, String> stressBase;
    /** Last layer: a large on-disk list for less frequent words. */
    private static BigStress bigStress;
    private static boolean bigStressTried;

    private final List<Pattern> patterns = new ArrayList<Pattern>();
    private final List<String> replacements = new ArrayList<String>();
    private final Map<String, String> words = new HashMap<String, String>();

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    static File importDir(Context c) {
        return new File(c.getFilesDir(), IMPORT_DIR);
    }

    static synchronized Lexicon get(Context c) {
        if (restore == null) {
            restore = loadPairs(c, RESTORE_ASSET, " ");
        }
        if (stressBase == null) {
            stressBase = loadPairs(c, STRESS_ASSET, " = ");
        }
        if (!bigStressTried) {
            bigStressTried = true;
            bigStress = BigStress.open(c);
        }
        String stamp = stamp(c);
        if (cached == null || !stamp.equals(cachedStamp)) {
            cached = build(c);
            cachedStamp = stamp;
        }
        return cached;
    }

    static File[] importedFiles(Context c) {
        File[] fs = importDir(c).listFiles();
        if (fs == null) {
            return new File[0];
        }
        Arrays.sort(fs);
        return fs;
    }

    private static String stamp(Context c) {
        StringBuilder sb = new StringBuilder();
        File u = file(c);
        sb.append(u.lastModified()).append(':').append(u.length());
        for (File f : importedFiles(c)) {
            sb.append('|').append(f.getName()).append(':').append(f.lastModified())
                    .append(':').append(f.length());
        }
        return sb.toString();
    }

    private static Lexicon build(Context c) {
        Lexicon lex = new Lexicon();
        Map<String, String> userWords = new HashMap<String, String>();
        lex.parse(readText(file(c)), userWords);
        for (File f : importedFiles(c)) {
            lex.parse(readText(f), lex.words);
        }
        lex.words.putAll(userWords);
        return lex;
    }

    private void parse(String text, Map<String, String> wordSink) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            String left = line.substring(0, eq).trim();
            String right = STRESS.matcher(line.substring(eq + 1).trim()).replaceAll(STRESSED);
            try {
                if (left.length() > 2 && left.startsWith("/") && left.endsWith("/")) {
                    patterns.add(Pattern.compile(left.substring(1, left.length() - 1), FLAGS));
                    replacements.add(right);
                } else if (SINGLE_WORD.matcher(left).matches()) {
                    wordSink.put(left.toLowerCase(Locale.ROOT), right);
                } else if (!left.isEmpty()) {
                    // Phrase: boundaries that also work for non-Latin letters.
                    patterns.add(Pattern.compile(
                            "(?<![\\p{L}\\p{N}])" + Pattern.quote(left) + "(?![\\p{L}\\p{N}])", FLAGS));
                    replacements.add(Matcher.quoteReplacement(right));
                }
            } catch (PatternSyntaxException e) {
                // A broken rule is skipped; the rest still apply.
            }
        }
    }

    /** Only the per-word layer: letter restoration and stress marks. */
    String applyWords(String text) {
        Matcher m = WORD.matcher(text);
        StringBuffer sb = new StringBuffer(text.length() + 16);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(lookup(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Only the user's pattern rules. Runs before normalization; stress marks
     * are added after it, because a mark inside a word hides the word from
     * the normalization rules (a name before a numeral, for instance).
     */
    String applyRules(String text) {
        String out = text;
        for (int i = 0; i < patterns.size(); i++) {
            try {
                out = patterns.get(i).matcher(out).replaceAll(replacements.get(i));
            } catch (RuntimeException e) {
                // Bad group reference in a replacement; skip this rule.
            }
        }
        return out;
    }

    String apply(String text) {
        String out = text;
        for (int i = 0; i < patterns.size(); i++) {
            try {
                out = patterns.get(i).matcher(out).replaceAll(replacements.get(i));
            } catch (RuntimeException e) {
                // Bad group reference in a replacement; skip this rule.
            }
        }
        if (words.isEmpty() && (restore == null || restore.isEmpty())
                && (stressBase == null || stressBase.isEmpty()) && bigStress == null) {
            return out;
        }
        Matcher m = WORD.matcher(out);
        StringBuffer sb = new StringBuffer(out.length() + 16);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(lookup(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Reads "key<separator>value" lines from an asset into a map. */
    private static Map<String, String> loadPairs(Context c, String asset, String separator) {
        Map<String, String> map = new HashMap<String, String>(1 << 18);
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    c.getAssets().open(asset), "UTF-8"), 1 << 16);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(separator);
                    if (sp > 0) {
                        map.put(line.substring(0, sp), line.substring(sp + separator.length()));
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // A missing built-in list only means fewer marks; everything else works.
        }
        return map;
    }

    /**
     * Drops a stress mark from yo: the letter is always stressed, and the
     * voice model only ever met it bare, so the mark turns a word it knows
     * into one it does not.
     */
    static String bareYo(String s) {
        return s.replace("\u0451\u0301", "\u0451").replace("\u0401\u0301", "\u0401");
    }

    /** The user's own and imported words, keyed without case or yo, built on first use. */
    private Map<String, String> ownFolded;

    private static String fold(String lower) {
        return lower.replace('\u0451', '\u0435');
    }

    /** The user's own or imported replacement for a word, written with or without yo; null if none. */
    private synchronized String own(String lower) {
        String hit = words.get(lower);
        if (hit != null) {
            return hit;
        }
        if (ownFolded == null) {
            ownFolded = new HashMap<String, String>();
            for (Map.Entry<String, String> e : words.entrySet()) {
                ownFolded.put(fold(e.getKey()), e.getValue());
            }
        }
        return ownFolded.get(fold(lower));
    }

    /** Whether the user's own or imported lists have this word; context rules leave such words alone. */
    boolean hasOwn(String lower) {
        return own(lower) != null;
    }

    private String lookup(String original) {
        if (original.indexOf(ACUTE) >= 0) {
            return original;
        }
        // The user's word comes first, before the yo list could respell the key it is filed under.
        String mine = own(original.toLowerCase(Locale.ROOT));
        if (mine != null) {
            return matchCase(original, mine);
        }
        String token = original;
        if (restore != null) {
            String fixed = restore.get(token.toLowerCase(Locale.ROOT));
            if (fixed != null) {
                token = matchCase(token, fixed);
            }
        }
        String hit = find(token.toLowerCase(Locale.ROOT));
        if (hit != null) {
            return matchCase(token, hit);
        }
        if (token.indexOf('-') < 0) {
            return token;
        }
        String[] parts = token.split("-", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            String p = find(parts[i].toLowerCase(Locale.ROOT));
            sb.append(p == null ? parts[i] : matchCase(parts[i], p));
        }
        return sb.toString();
    }

    private String find(String key) {
        String hit = words.get(key);
        if (hit == null && stressBase != null) {
            hit = stressBase.get(key);
        }
        if (hit == null && bigStress != null) {
            int at = bigStress.stressOf(key);
            if (at >= 0 && at < key.length()) {
                hit = key.substring(0, at + 1) + ACUTE + key.substring(at + 1);
            }
        }
        return hit;
    }

    /**
     * Carries the capitalization of the original word over to the replacement,
     * letter by letter, so both halves of a double name keep their capitals.
     * Stress marks in the replacement do not count as letters.
     */
    static String matchCase(String original, String replacement) {
        if (original.isEmpty() || replacement.isEmpty()) {
            return replacement;
        }
        String bare = replacement.replace("\u0301", "");
        if (bare.length() == original.length()) {
            StringBuilder sb = new StringBuilder(replacement.length());
            int i = 0;
            for (int j = 0; j < replacement.length(); j++) {
                char r = replacement.charAt(j);
                if (r == '\u0301') {
                    sb.append(r);
                    continue;
                }
                sb.append(Character.isUpperCase(original.charAt(i)) ? Character.toUpperCase(r) : r);
                i++;
            }
            return sb.toString();
        }
        boolean allUpper = original.length() > 1 && original.equals(original.toUpperCase(Locale.ROOT));
        if (allUpper) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return replacement.substring(0, 1).toUpperCase(Locale.ROOT) + replacement.substring(1);
        }
        return replacement;
    }

    static String readText(Context c) {
        return readText(file(c));
    }

    static String readText(File f) {
        if (!f.isFile()) {
            return "";
        }
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
                return new String(buf, 0, off, "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    static void writeText(Context c, String text) throws IOException {
        writeFile(file(c), text);
    }

    static void writeFile(File target, String text) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(target.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("rename failed");
        }
    }
}
