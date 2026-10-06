package com.shumtugle.hora;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a user-supplied pronunciation dictionary in the common
 * quoted "key"="value" format into this app's rule format.
 *
 * Values may be plain text (used as is) or a phonetic transcription in one of
 * three notations: IPA inside a phoneme tag, SAMPA in a tagged group, or a
 * syllable notation between slash-plus marks. From a transcription only the
 * stress position is taken, and written back into the word as an accent mark.
 * Transcriptions of multi-word keys are skipped: stress cannot be placed
 * reliably across words.
 */
final class LexxImport {
    private static final Pattern IPA = Pattern.compile("ph=\"([^\"]*)\"");
    private static final Pattern SAMPA = Pattern.compile("\\\\SAMPA=\\(([^)]*)\\)");
    private static final Pattern SYLLABLES = Pattern.compile("/\\+(.*?)/\\+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:-[\\p{L}\\p{M}]+)*");

    private static final String IPA_VOWELS = "aeiouy\u0250\u0259\u025b\u0268\u026a\u028a\u00e6\u0251\u0254\u028c\u0275\u00f8\u0153\u026f";
    private static final String SAMPA_VOWELS = "aeiouyAEIOU@18V";

    private final String vowels;
    private final String alwaysStressed;

    static final class Result {
        final String name;
        final int rules;

        Result(String name, int rules) {
            this.name = name;
            this.rules = rules;
        }
    }

    LexxImport(Resources speech) {
        vowels = speech.getString(R.string.stress_vowels);
        alwaysStressed = speech.getString(R.string.always_stressed);
    }

    Result importUri(Context c, Uri uri, String displayName) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("cannot open");
        }
        String text;
        try {
            text = decode(readAll(in));
        } finally {
            in.close();
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String raw : text.split("\n")) {
            String rule = convert(raw.trim());
            if (rule != null) {
                out.append(rule).append('\n');
                count++;
            }
        }
        String base = displayName == null ? "dictionary" : displayName;
        base = base.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        if (base.isEmpty()) {
            base = "dictionary";
        }
        Lexicon.writeFile(new File(Lexicon.importDir(c), base + ".txt"), out.toString());
        return new Result(base, count);
    }

    /** One source line to one rule line, or null when the line carries nothing usable. */
    String convert(String line) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
            return null;
        }
        String key;
        String value;
        if (line.startsWith("\"")) {
            int close = line.indexOf('"', 1);
            if (close < 0) {
                return null;
            }
            key = line.substring(1, close).trim();
            int eq = line.indexOf('=', close);
            if (eq < 0) {
                return null;
            }
            value = line.substring(eq + 1).trim();
        } else {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            key = line.substring(0, eq).trim();
            value = line.substring(eq + 1).trim();
        }
        // Quotes may be unbalanced in real files: strip each side on its own.
        value = value.trim();
        if (value.startsWith("\"")) {
            value = value.substring(1);
        }
        if (value.endsWith("\"")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.trim();
        if (key.isEmpty() || value.isEmpty() || key.indexOf('=') >= 0) {
            return null;
        }

        int stress;
        Matcher m;
        if ((m = IPA.matcher(value)).find()) {
            stress = markedVowelIndex(m.group(1), "'\u02c8", IPA_VOWELS);
        } else if ((m = SAMPA.matcher(value)).find()) {
            stress = markedVowelIndex(m.group(1), "\"", SAMPA_VOWELS);
        } else if ((m = SYLLABLES.matcher(value + "/+")).find() && value.startsWith("/+")) {
            stress = stressedSyllable(m.group(1));
        } else if (value.contains("/+") || value.contains("\\SAMPA") || value.contains("<phoneme")) {
            // A transcription this importer could not read: never pass it through as text.
            return null;
        } else {
            // Plain text replacement: keep it unless it is identical to the key.
            return value.equalsIgnoreCase(key) ? null : key + " = " + value;
        }
        String accented = placeStress(key, stress);
        return accented == null ? null : key.toLowerCase(Locale.ROOT) + " = " + accented;
    }

    private String placeStress(String key, int index) {
        if (index < 0 || !WORD.matcher(key).matches()) {
            return null;
        }
        String w = key.toLowerCase(Locale.ROOT);
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            // Mixed-script keys: the transcription counts vowels the key does not show.
            if (alwaysStressed.indexOf(ch) >= 0 || (ch < 128 && Character.isLetter(ch))) {
                return null;
            }
        }
        int seen = 0;
        int total = 0;
        for (int i = 0; i < w.length(); i++) {
            if (vowels.indexOf(w.charAt(i)) >= 0) {
                total++;
            }
        }
        if (total < 2 || index >= total) {
            return null;
        }
        for (int i = 0; i < w.length(); i++) {
            if (vowels.indexOf(w.charAt(i)) >= 0) {
                if (seen == index) {
                    return w.substring(0, i + 1) + Lexicon.ACUTE + w.substring(i + 1);
                }
                seen++;
            }
        }
        return null;
    }

    /** Number of vowels before the stress mark: the index of the stressed vowel. */
    private static int markedVowelIndex(String phones, String marks, String vowelSet) {
        int count = 0;
        for (int i = 0; i < phones.length(); i++) {
            char ch = phones.charAt(i);
            if (marks.indexOf(ch) >= 0) {
                return count;
            }
            if (vowelSet.indexOf(ch) >= 0) {
                count++;
            }
        }
        return -1;
    }

    private static int stressedSyllable(String syllables) {
        String[] parts = syllables.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].indexOf('\'') >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String decode(byte[] b) throws IOException {
        int off = b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb
                && (b[2] & 0xff) == 0xbf ? 3 : 0;
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) {
            return new String(b, 2, b.length - 2, "UTF-16LE");
        }
        return new String(b, off, b.length - off, "UTF-8").replace("\r", "");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
