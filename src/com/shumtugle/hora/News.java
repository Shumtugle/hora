package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Headlines from one public broadcaster, for the Day: titles only, never the
 * stories, asked for at most every half hour and only while the screen is
 * open. Shown only when the developer room has them switched on. The
 * broadcasters and their addresses live in the bundle.
 */
final class News {
    private static final String PREFS = "news";
    private static final String ON = "on";
    private static final String SOURCE = "source";
    private static final long FRESH_MS = 30 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** How many headlines the Day holds; the newest come first in every feed. */
    static final int MAX = 15;

    /** A broadcaster: a short key, the name shown, and where its headlines are. */
    static final class Source {
        final String id;
        final String name;
        final String url;

        Source(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    /** The last headlines, with when and from whom they came, as one snapshot read without a lock. */
    static final class Kept {
        final String source;
        final long at;
        final List<String> titles;

        Kept(String source, long at, List<String> titles) {
            this.source = source;
            this.at = at;
            this.titles = Collections.unmodifiableList(titles);
        }
    }

    private static volatile Kept kept;
    private static volatile List<Source> sources;

    private News() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        return sp(c).getBoolean(ON, false);
    }

    static void setOn(Context c, boolean on) {
        sp(c).edit().putBoolean(ON, on).apply();
    }

    static void setSource(Context c, String id) {
        sp(c).edit().putString(SOURCE, id).apply();
    }

    /** The broadcasters from the bundle, in its order; empty when it cannot be read. */
    static List<Source> sources(Context c) {
        List<Source> s = sources;
        if (s != null) {
            return s;
        }
        List<Source> found = new ArrayList<Source>();
        try {
            String all = readAll(c.getAssets().open("talk/news.txt"), "UTF-8", 0);
            for (String line : all.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                if (p.length >= 3) {
                    found.add(new Source(p[0].trim(), p[1].trim(), p[2].trim()));
                }
            }
        } catch (IOException e) {
            Diag.log(c, "news: the list of broadcasters could not be read", e);
        }
        sources = Collections.unmodifiableList(found);
        return sources;
    }

    /** The chosen broadcaster, or the first one; null when there are none. */
    static Source source(Context c) {
        List<Source> all = sources(c);
        if (all.isEmpty()) {
            return null;
        }
        String id = sp(c).getString(SOURCE, "");
        for (Source s : all) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return all.get(0);
    }

    /** What was fetched lately from the chosen broadcaster, without the network; null when nothing is kept. */
    static Kept kept(Context c) {
        Kept k = kept;
        Source s = source(c);
        return k != null && s != null && k.source.equals(s.id) ? k : null;
    }

    /**
     * Fresh headlines from the chosen broadcaster, from the network when what is kept is older than
     * half an hour; what was kept when the network fails; null when there is nothing. Off the main thread.
     */
    static synchronized Kept fetch(Context c) {
        Source s = source(c);
        if (s == null) {
            return null;
        }
        Kept k = kept;
        long now = System.currentTimeMillis();
        if (k != null && k.source.equals(s.id) && now >= k.at && now - k.at < FRESH_MS) {
            return k;
        }
        try {
            List<String> titles = parse(get(c, s.url));
            if (titles.isEmpty()) {
                throw new IOException("no headlines");
            }
            Kept fresh = new Kept(s.id, now, titles);
            kept = fresh;
            // How many and from whom; the headlines themselves stay out of the journal.
            Diag.log(c, "news: " + titles.size() + " headlines from " + s.id);
            return fresh;
        } catch (Exception e) {
            Diag.log(c, "news: no answer from " + s.id + ", " + e.getClass().getSimpleName());
            return k != null && k.source.equals(s.id) ? k : null;
        }
    }

    // ---- Reading a feed ----

    /** How many items a feed may offer before the rest is left unread, titled or not. */
    private static final int MAX_ITEMS = 300;
    /** A title longer than this is no headline; it is cut before it is read. */
    private static final int MAX_TITLE = 2000;

    /**
     * The titles of the items of a feed, newest first as the feed has them: each item's or entry's
     * first title, its markup and entities undone, its spaces made single. Works on the common
     * feed shapes without a full XML reader, in one pass, and drops what it cannot read.
     */
    static List<String> parse(String xml) {
        List<String> out = new ArrayList<String>();
        if (xml == null) {
            return out;
        }
        xml = withoutComments(xml);
        int at = 0;
        int item = nextOpen(xml, 0, xml.length(), "item");
        int entry = nextOpen(xml, 0, xml.length(), "entry");
        int seen = 0;
        while (out.size() < MAX && seen < MAX_ITEMS) {
            // Each kind is looked for again only once the reading has passed it, so the text is crossed once.
            if (item >= 0 && item < at) {
                item = nextOpen(xml, at, xml.length(), "item");
            }
            if (entry >= 0 && entry < at) {
                entry = nextOpen(xml, at, xml.length(), "entry");
            }
            int start = item < 0 ? entry : entry < 0 ? item : Math.min(item, entry);
            if (start < 0) {
                break;
            }
            seen++;
            String tag = start == item ? "item" : "entry";
            int end = xml.indexOf("</" + tag, start);
            int stop = end < 0 ? xml.length() : end;
            String title = firstTitle(xml, start, stop);
            if (title != null && !title.isEmpty() && !out.contains(title)) {
                out.add(title);
            }
            at = end < 0 ? xml.length() : end + tag.length() + 2;
        }
        return out;
    }

    /** The text with every comment taken out, in one pass: an item in a comment is no item. */
    private static String withoutComments(String xml) {
        int open = xml.indexOf("<!--");
        if (open < 0) {
            return xml;
        }
        StringBuilder b = new StringBuilder(xml.length());
        int at = 0;
        int cdata = xml.indexOf("<![CDATA[");
        while (open >= 0) {
            // A comment inside character data is part of the text, not a comment.
            if (cdata >= 0 && cdata < at) {
                cdata = xml.indexOf("<![CDATA[", at);
            }
            if (cdata >= 0 && cdata < open) {
                int cend = xml.indexOf("]]>", cdata);
                int to = cend < 0 ? xml.length() : cend + 3;
                b.append(xml, at, to);
                at = to;
                open = xml.indexOf("<!--", at);
                continue;
            }
            b.append(xml, at, open);
            int close = xml.indexOf("-->", open + 4);
            at = close < 0 ? xml.length() : close + 3;
            open = at < xml.length() ? xml.indexOf("<!--", at) : -1;
        }
        if (at < xml.length()) {
            b.append(xml, at, xml.length());
        }
        return b.toString();
    }

    /**
     * Where the next opening tag with this name starts before the limit, a name such as "item"
     * but not "items"; -1 if none.
     */
    private static int nextOpen(String xml, int from, int to, String name) {
        int i = from;
        while (i < to) {
            i = xml.indexOf("<" + name, i);
            if (i < 0 || i >= to) {
                return -1;
            }
            int after = i + 1 + name.length();
            if (after < xml.length()) {
                char ch = xml.charAt(after);
                if (ch == '>' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '/') {
                    return i;
                }
            }
            i = after;
        }
        return -1;
    }

    /** The item's own title: one inside the character data of another part, a page's markup, is passed by. */
    private static String firstTitle(String xml, int from, int to) {
        // Only the item itself is searched, so a feed without the closing marks is never crossed again.
        String body = xml.substring(from, Math.min(to, xml.length()));
        int i = 0;
        int open = nextOpen(body, 0, body.length(), "title");
        while (i < body.length()) {
            // The next title is looked for again only once the reading has passed it.
            if (open >= 0 && open < i) {
                open = nextOpen(body, i, body.length(), "title");
            }
            if (open < 0) {
                return null;
            }
            int cdata = body.indexOf("<![CDATA[", i);
            if (cdata >= 0 && cdata < open) {
                int cend = body.indexOf("]]>", cdata);
                // A title inside character data, or after it: go on past the character data either way.
                i = cend < 0 ? body.length() : cend + 3;
                continue;
            }
            int gt = body.indexOf('>', open);
            if (gt < 0 || body.charAt(gt - 1) == '/') {
                return null;
            }
            int close = body.indexOf("</title", gt);
            if (close < 0) {
                return null;
            }
            int cut = Math.min(close, gt + 1 + MAX_TITLE);
            return clean(body.substring(gt + 1, cut));
        }
        return null;
    }

    /** Character data unwrapped, entities undone (twice, for feeds that escape twice), tags dropped, spaces made single. */
    static String clean(String raw) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int cdata = raw.indexOf("<![CDATA[", i);
            if (cdata < 0) {
                text.append(entities(raw.substring(i)));
                break;
            }
            text.append(entities(raw.substring(i, cdata)));
            int end = raw.indexOf("]]>", cdata);
            if (end < 0) {
                text.append(raw.substring(cdata + 9));
                break;
            }
            text.append(raw, cdata + 9, end);
            i = end + 3;
        }
        String s = text.toString();
        if (s.indexOf('&') >= 0) {
            s = entities(s);
        }
        // Markup some feeds put inside a title is dropped; a lone sign such as "<" in "rates < 3%" stays.
        s = s.replaceAll("</?[A-Za-z][^<>]{0,200}>", " ").replaceAll("(?s)<!--.{0,200}?-->", " ");
        StringBuilder kept = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (Character.isSurrogate(ch)) {
                // Only whole pairs pass; a lone half is no character a voice can say.
                if (Character.isHighSurrogate(ch) && k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1))) {
                    kept.append(ch).append(s.charAt(++k));
                }
                continue;
            }
            kept.append(ch < 0x20 ? ' ' : ch);
        }
        return kept.toString().replaceAll("[\\s\\u00a0\\u2009]+", " ").replace("\u00ad", "").trim();
    }

    private static final String[][] NAMED = {
            {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"}, {"nbsp", " "},
            {"laquo", "\u00ab"}, {"raquo", "\u00bb"}, {"mdash", "\u2014"}, {"ndash", "\u2013"},
            {"hellip", "\u2026"}, {"ldquo", "\u201c"}, {"rdquo", "\u201d"}, {"bdquo", "\u201e"},
            {"lsquo", "\u2018"}, {"rsquo", "\u2019"}, {"shy", ""}, {"thinsp", " "}, {"minus", "\u2212"},
    };

    private static String entities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '&') {
                b.append(ch);
                i++;
                continue;
            }
            // An entity is short: look for its end only a few letters ahead.
            int semi = -1;
            for (int k = i + 1; k < s.length() && k <= i + 10; k++) {
                if (s.charAt(k) == ';') {
                    semi = k;
                    break;
                }
            }
            String put = null;
            if (semi > 0) {
                String name = s.substring(i + 1, semi);
                if (name.startsWith("#")) {
                    try {
                        int code = name.startsWith("#x") || name.startsWith("#X")
                                ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                        // A number that is no character a voice can say is dropped, not spelled out.
                        if (code >= 0x80 && code <= 0x9F) {
                            // Old pages number dashes and quotes by the western code page, as browsers read them.
                            String w = new String(new byte[] {(byte) code}, Charset.forName("windows-1252"));
                            put = w.length() == 1 && w.charAt(0) >= 0xA0 && w.charAt(0) != 0xFFFD ? w : "";
                        } else {
                            put = Character.isValidCodePoint(code) && (code >= 0x20 || code == 9 || code == 10 || code == 13)
                                    && !(code >= 0xD800 && code <= 0xDFFF) ? new String(Character.toChars(code)) : "";
                        }
                    } catch (NumberFormatException e) {
                        put = null;
                    }
                } else {
                    for (String[] n : NAMED) {
                        if (n[0].equals(name)) {
                            put = n[1];
                            break;
                        }
                    }
                }
            }
            if (put == null) {
                b.append(ch);
                i++;
            } else {
                b.append(put);
                i = semi + 1;
            }
        }
        return b.toString();
    }

    // ---- The network ----

    /** The whole asking, connection and reading together, may take no longer than this. */
    private static final long DEADLINE_MS = 20000;

    private static String get(Context c, String address) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setInstanceFollowRedirects(true);
        http.setUseCaches(false);
        try {
            // The same name the other readers of the network give.
            http.setRequestProperty("User-Agent",
                    readAll(c.getAssets().open("talk/wiki-agent.txt"), "UTF-8", 0).trim());
        } catch (IOException e) {
            // Without the name the request still goes.
        }
        long until = System.currentTimeMillis() + DEADLINE_MS;
        try {
            int code = http.getResponseCode();
            if (code != 200) {
                throw new IOException("status " + code);
            }
            byte[] body = readBytes(http.getInputStream(), until);
            return new String(body, charset(body, http.getContentType()));
        } finally {
            http.disconnect();
        }
    }

    /**
     * The text's encoding: a byte order mark first, then what the feed says of itself in its first
     * line, then what the server says, then UTF-8.
     */
    static Charset charset(byte[] b, String contentType) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return Charset.forName("UTF-8");
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return Charset.forName("UTF-16BE");
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return Charset.forName("UTF-16LE");
        }
        String head = new String(b, 0, Math.min(b.length, 200), Charset.forName("ISO-8859-1")).trim();
        if (head.startsWith("<?xml")) {
            int end = head.indexOf("?>");
            String prolog = end > 0 ? head.substring(0, end) : head;
            Charset named = named(prolog, "encoding=");
            if (named != null) {
                return named;
            }
        }
        if (contentType != null) {
            Charset named = named(contentType, "charset=");
            if (named != null) {
                return named;
            }
        }
        return Charset.forName("UTF-8");
    }

    private static Charset named(String text, String key) {
        // "encoding = 'x'" is written with spaces round the sign as often as without.
        String lower = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s*=\\s*", "=");
        String plain = text.replaceAll("\\s*=\\s*", "=");
        int at = lower.indexOf(key);
        if (at < 0) {
            return null;
        }
        String v = plain.substring(at + key.length()).trim();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\'') {
                if (name.length() > 0) {
                    break;
                }
                continue;
            }
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' || ch == ':')) {
                break;
            }
            name.append(ch);
        }
        try {
            return name.length() > 0 && Charset.isSupported(name.toString()) ? Charset.forName(name.toString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] readBytes(InputStream in, long until) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new IOException("too long");
                }
                if (until > 0 && System.currentTimeMillis() > until) {
                    throw new IOException("too slow");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String readAll(InputStream in, String charset, long until) throws IOException {
        return new String(readBytes(in, until), charset);
    }
}
