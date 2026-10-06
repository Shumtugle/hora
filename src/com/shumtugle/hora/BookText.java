package com.shumtugle.hora;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a book file into paragraphs of plain text. Works on bytes alone, no
 * platform parsers, so every format can be checked off the device. Markup is
 * dropped; what the voice needs is the sentence and where paragraphs break.
 */
final class BookText {
    /** Larger files are cut here: a book is rarely more, and memory is finite. */
    static final int MAX_BYTES = 48 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LEGACY = "windows-1251";

    private static final Pattern DECLARED = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r\\u00a0]+");
    private static final Pattern ITEM = Pattern.compile("(?i)<item\\s[^>]*>");
    private static final Pattern ITEMREF = Pattern.compile("(?i)<itemref\\s[^>]*>");

    private static final Map<String, String> NAMED = new HashMap<String, String>();

    static {
        NAMED.put("nbsp", " ");
        NAMED.put("mdash", "\u2014");
        NAMED.put("ndash", "\u2013");
        NAMED.put("laquo", "\u00ab");
        NAMED.put("raquo", "\u00bb");
        NAMED.put("hellip", "\u2026");
        NAMED.put("amp", "&");
        NAMED.put("lt", "<");
        NAMED.put("gt", ">");
        NAMED.put("quot", "\"");
        NAMED.put("apos", "'");
        NAMED.put("bdquo", "\u201e");
        NAMED.put("ldquo", "\u201c");
        NAMED.put("rdquo", "\u201d");
        NAMED.put("shy", "");
    }

    private BookText() {
    }

    /** A title from a file name: the extensions go, underscores become spaces. */
    static String title(String name) {
        if (name == null) {
            return "";
        }
        String t = name;
        String lower = t.toLowerCase(Locale.ROOT);
        String[] ends = {".fb2.zip", ".fb2", ".fb3", ".epub", ".txt", ".md", ".docx", ".odt", ".rtf", ".html", ".htm", ".xhtml"};
        for (String e : ends) {
            if (lower.endsWith(e)) {
                t = t.substring(0, t.length() - e.length());
                break;
            }
        }
        return t.replace('_', ' ').trim();
    }

    /** Whether a file name looks like something the reader can open. */
    static boolean readable(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".fb2") || n.endsWith(".fb2.zip") || n.endsWith(".fb3")
                || n.endsWith(".epub") || n.endsWith(".docx") || n.endsWith(".odt") || n.endsWith(".rtf")
                || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml");
    }

    /** The paragraphs of a book, or an empty list if nothing readable was found. */
    static List<String> paragraphs(String name, InputStream in) throws IOException {
        return read(name, in, "", "").paragraphs;
    }

    /**
     * A whole book: text, chapters, and title and author from the file's own
     * description where it has one (book description, package metadata).
     */
    static Book read(String name, InputStream in, String chapterWords, String ordinals) throws IOException {
        byte[] data = readAll(in, MAX_BYTES);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String text = text(name, data);
        if (n.endsWith(".md")) {
            text = text.replaceAll("(?m)^#{1,3}\\s+", String.valueOf(Book.HEADING));
        }
        String metaTitle = "";
        String metaAuthor = "";
        if (n.endsWith(".fb2")) {
            String xml = decodeXml(data);
            String info = Book.raw(xml, "title-info");
            metaTitle = Book.inner(info, "book-title");
            String author = Book.raw(info, "author");
            metaAuthor = (Book.inner(author, "first-name") + " " + Book.inner(author, "last-name")).trim();
        } else if (n.endsWith(".fb3")) {
            // The description sits beside the body: the title's main line, and the subject linked as author.
            String desc = new String(entry(data, "description.xml"), UTF8);
            metaTitle = Book.inner(Book.raw(desc, "title"), "main");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?s)<subject[^>]*link=\"author\"[^>]*>(.*?)</subject>").matcher(desc);
            if (m.find()) {
                String who = m.group(1);
                String first = Book.inner(who, "first-name");
                String last = Book.inner(who, "last-name");
                metaAuthor = (first + " " + last).trim();
                if (metaAuthor.isEmpty()) {
                    metaAuthor = Book.inner(Book.raw(who, "title"), "main");
                }
            }
        } else if (n.endsWith(".epub")) {
            String opf = opf(data);
            metaTitle = entities(Book.inner(opf, "dc:title"));
            metaAuthor = entities(Book.inner(opf, "dc:creator"));
        }
        return Book.of(split(text), title(name), entities(metaTitle), entities(metaAuthor),
                chapterWords == null || chapterWords.isEmpty() ? "\\u0000" : chapterWords,
                ordinals == null || ordinals.isEmpty() ? "\\u0000" : ordinals);
    }

    /** The package description of an epub, or "". */
    private static String opf(byte[] data) throws IOException {
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                    return new String(readAll(z, MAX_BYTES), UTF8);
                }
            }
        } finally {
            z.close();
        }
        return "";
    }

    /** Plain text of a book, paragraphs separated by line breaks. */
    static String text(String name, byte[] data) throws IOException {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) {
            return epub(data);
        }
        if (n.endsWith(".docx")) {
            return wrapped(data, "word/document.xml", "w:p");
        }
        if (n.endsWith(".odt")) {
            return wrapped(data, "content.xml", "text:p");
        }
        if (n.endsWith(".zip")) {
            return zipped(data);
        }
        if (n.endsWith(".fb2")) {
            return fb2(decodeXml(data));
        }
        if (n.endsWith(".fb3")) {
            return fb3(data);
        }
        if (n.endsWith(".rtf")) {
            return rtf(new String(data, "ISO-8859-1"));
        }
        if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml")) {
            return html(decodeXml(data));
        }
        return plain(data);
    }

    /** Non-empty lines, trimmed, spaces squeezed. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String s = SPACES.matcher(line).replaceAll(" ").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Plain text in whatever encoding it came: a byte-order mark, valid UTF-8, or the legacy Cyrillic page. */
    static String plain(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf) {
            return new String(b, 3, b.length - 3, UTF8);
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) {
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        }
        try {
            return UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName(LEGACY));
        }
    }

    /** XML declares its encoding; without a declaration it is UTF-8 or, failing that, the legacy page. */
    private static String decodeXml(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 300), Charset.forName("ISO-8859-1"));
        Matcher m = DECLARED.matcher(head);
        if (m.find()) {
            try {
                return new String(b, Charset.forName(m.group(1)));
            } catch (RuntimeException e) {
                // Unknown name: guess as for plain text.
            }
        }
        return plain(b);
    }

    /**
     * Fiction book format: the main body only (notes live in a second body),
     * with paragraphs, titles, verse lines, epigraph authors each on a line of
     * their own; pictures and note references are dropped.
     */
    static String fb2(String xml) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int open = xml.indexOf("<body", from);
            if (open < 0) {
                break;
            }
            int headEnd = xml.indexOf('>', open);
            int close = xml.indexOf("</body>", headEnd);
            if (headEnd < 0 || close < 0) {
                break;
            }
            String head = xml.substring(open, headEnd);
            from = close + 7;
            if (head.contains("name=\"notes\"") || head.contains("name=\"comments\"")) {
                continue;
            }
            String body = xml.substring(headEnd + 1, close);
            body = body.replaceAll("(?s)<a\\s[^>]*type=\"note\"[^>]*>.*?</a>", "");
            body = body.replaceAll("(?s)<binary[^>]*>.*?</binary>", "");
            body = body.replaceAll("(?i)<title>", "<title>" + Book.HEADING);
            body = body.replaceAll("(?i)</(p|v|subtitle|text-author|title|stanza)>", "\n");
            body = body.replaceAll("(?i)<empty-line\\s*/>", "\n");
            out.append(entities(TAG.matcher(body).replaceAll(""))).append('\n');
        }
        return out.toString();
    }

    /**
     * An fb3 is a zip whose body is fb2's younger sibling: the same sections, titles and
     * paragraphs under another root. The root is renamed and the fb2 reading does the rest.
     */
    static String fb3(byte[] data) throws IOException {
        String xml = new String(entry(data, "body.xml"), UTF8);
        int open = xml.indexOf("<fb3-body");
        int close = xml.lastIndexOf("</fb3-body>");
        if (open < 0 || close < 0) {
            return "";
        }
        int headEnd = xml.indexOf('>', open);
        String body = xml.substring(headEnd + 1, close)
                .replaceAll("(?s)<notes[^>]*>.*?</notes>", "")
                .replaceAll("(?s)<note\\s[^>]*>.*?</note>", "");
        return fb2("<body>" + body + "</body>");
    }

    /** The bytes of the first zip entry whose name ends with the given ending, or none. */
    private static byte[] entry(byte[] data, String ending) throws IOException {
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(ending)) {
                    return readAll(z, MAX_BYTES);
                }
            }
        } finally {
            z.close();
        }
        return new byte[0];
    }

    private static String zipped(byte[] data) throws IOException {
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".fb2") || n.endsWith(".txt")) {
                    byte[] inner = readAll(z, MAX_BYTES);
                    return n.endsWith(".fb2") ? fb2(decodeXml(inner)) : plain(inner);
                }
            }
        } finally {
            z.close();
        }
        return "";
    }

    /** An epub is a zip of pages with a manifest; the spine gives their reading order. */
    static String epub(byte[] data) throws IOException {
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".opf")) {
                    files.put(e.getName(), readAll(z, MAX_BYTES));
                }
            }
        } finally {
            z.close();
        }
        List<String> order = new ArrayList<String>();
        for (String k : files.keySet()) {
            if (!k.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                continue;
            }
            String opf = new String(files.get(k), UTF8);
            Map<String, String> byId = new HashMap<String, String>();
            Matcher m = ITEM.matcher(opf);
            while (m.find()) {
                String id = attr(m.group(), "id");
                String href = attr(m.group(), "href");
                if (id != null && href != null) {
                    byId.put(id, href);
                }
            }
            String base = k.contains("/") ? k.substring(0, k.lastIndexOf('/') + 1) : "";
            Matcher r = ITEMREF.matcher(opf);
            while (r.find()) {
                String id = attr(r.group(), "idref");
                String href = id == null ? null : byId.get(id);
                if (href == null) {
                    continue;
                }
                href = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
                String full = normalize(base + href);
                if (files.containsKey(full)) {
                    order.add(full);
                } else if (files.containsKey(href)) {
                    order.add(href);
                }
            }
            break;
        }
        if (order.isEmpty()) {
            for (String k : files.keySet()) {
                if (!k.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                    order.add(k);
                }
            }
            java.util.Collections.sort(order);
        }
        StringBuilder sb = new StringBuilder();
        for (String k : order) {
            sb.append(html(decodeXml(files.get(k)))).append('\n');
        }
        return sb.toString();
    }

    /** Resolves "a/b/../c" to "a/c". */
    private static String normalize(String path) {
        List<String> parts = new ArrayList<String>();
        for (String p : path.split("/")) {
            if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** A web page: block ends break lines, the rest of the markup goes. */
    static String html(String s) {
        String t = s.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        t = t.replaceAll("(?i)<h[1-3](\\s[^>]*)?>", "\n" + Book.HEADING);
        t = t.replaceAll("(?i)</(p|div|h[1-6]|li|blockquote|tr)\\s*>", "\n");
        t = t.replaceAll("(?i)<br\\s*/?>", "\n");
        return entities(TAG.matcher(t).replaceAll(""));
    }

    /** Office documents are zips with one xml of interest; only the entry and the paragraph tag differ. */
    private static String wrapped(byte[] data, String entry, String para) throws IOException {
        ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(entry)) {
                    String xml = new String(readAll(z, MAX_BYTES), UTF8);
                    xml = xml.replaceAll("(?i)</" + para + ">", "\n").replaceAll("(?i)<w:br[^>]*>", "\n");
                    return entities(TAG.matcher(xml).replaceAll(""));
                }
            }
        } finally {
            z.close();
        }
        return "";
    }

    /** Rich text: text between control words; Cyrillic arrives as escaped bytes of the legacy page. */
    static String rtf(String raw) {
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        int depth = 0;
        int skipDepth = -1;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '\\') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                    if (skipDepth < 0 && i + 4 <= raw.length()) {
                        try {
                            bytes.write(Integer.parseInt(raw.substring(i + 2, i + 4), 16));
                        } catch (NumberFormatException ignored) {
                            // A broken escape is skipped.
                        }
                    }
                    i += 4;
                    continue;
                }
                int j = i + 1;
                StringBuilder word = new StringBuilder();
                while (j < raw.length() && Character.isLetter(raw.charAt(j))) {
                    word.append(raw.charAt(j));
                    j++;
                }
                StringBuilder num = new StringBuilder();
                while (j < raw.length() && (Character.isDigit(raw.charAt(j)) || raw.charAt(j) == '-')) {
                    num.append(raw.charAt(j));
                    j++;
                }
                if (j < raw.length() && raw.charAt(j) == ' ') {
                    j++;
                }
                String w = word.toString();
                flush(bytes, out);
                if (skipDepth >= 0) {
                    i = j;
                    continue;
                }
                if (w.equals("par") || w.equals("line")) {
                    out.append('\n');
                } else if (w.equals("u") && num.length() > 0) {
                    try {
                        int cp = Integer.parseInt(num.toString());
                        out.append((char) (cp < 0 ? cp + 65536 : cp));
                        if (j < raw.length() && raw.charAt(j) == '?') {
                            j++;
                        }
                    } catch (NumberFormatException ignored) {
                        // Skipped.
                    }
                } else if (w.equals("fonttbl") || w.equals("colortbl") || w.equals("stylesheet")
                        || w.equals("info") || w.equals("pict")) {
                    skipDepth = depth;
                } else if (word.length() == 0 && j < raw.length()) {
                    char lit = raw.charAt(j);
                    if (lit == '\\' || lit == '{' || lit == '}') {
                        out.append(lit);
                        j++;
                    }
                }
                i = j;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                if (skipDepth >= 0 && depth <= skipDepth) {
                    skipDepth = -1;
                }
                depth--;
            } else if (skipDepth < 0 && ch != '\r' && ch != '\n') {
                bytes.write(ch);
            }
            i++;
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream b, StringBuilder out) {
        if (b.size() > 0) {
            out.append(new String(b.toByteArray(), Charset.forName(LEGACY)));
            b.reset();
        }
    }

    static String entities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#")) {
                try {
                    int cp = e.startsWith("#x") || e.startsWith("#X")
                            ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                    r = new String(Character.toChars(cp));
                } catch (RuntimeException ex) {
                    r = m.group();
                }
            } else {
                r = NAMED.containsKey(e) ? NAMED.get(e) : m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = limit - out.size();
            if (room <= 0) {
                break;
            }
            out.write(buf, 0, Math.min(n, room));
        }
        return out.toByteArray();
    }
}
