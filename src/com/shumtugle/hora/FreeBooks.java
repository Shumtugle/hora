package com.shumtugle.hora;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Books from the free library, the encyclopedia's sister: works whose rights have run out, and
 * translations given to everyone. A search in Latin letters goes to the English library, any
 * other to the Russian one. A work is put together here from its pages: its front page, and its
 * parts when it is split into chapters, nested at most three deep. The result is one book file
 * for the shelf. Only reading, never on the main thread.
 *
 * The address and the name the app gives itself live in the bundle.
 */
final class FreeBooks {
    /** How many results the search shows. */
    private static final int SHOWN = 15;
    /** At most this many pages per book, and this much text: a long novel fits, a whole archive does not. */
    private static final int MAX_PAGES = 400;
    private static final int MAX_CHARS = 4_000_000;
    /** A part with less text of its own than this, and parts of its own, is a table of contents. */
    private static final int CONTENTS = 1500;
    /** Less than this beside the contents is no text of its own: a heading, a line of navigation. */
    private static final int SCRAP = 200;
    /** A front page keeps even a short text of its own: an epigraph, a dedication. */
    private static final int FRONT = 40;
    private static final int DEPTH = 3;
    /** Parts that may fail before the whole book is given up: a quarter, and never fewer than this. */
    private static final int FAILS = 5;
    /** Parts failing one after another mean the network is gone: the book is given up at once. */
    private static final int FAILS_IN_A_ROW = 3;
    /** After a part's name on a contents line: its page number, punctuation, nothing that is text. */
    private static final java.util.regex.Pattern TAIL = java.util.regex.Pattern.compile("[\\s\\d.,:;\\u2013\\u2014\\-]*");

    private FreeBooks() {
    }

    /** One search result: the page, its title as shown, and what the page name adds (often the author). */
    static final class Found {
        String page;
        String title;
        String by;
        String lang;
    }

    /** A work about to be put on the shelf; its front page is kept from the first look. */
    static final class Work {
        String page;
        String lang;
        String title;
        String author;
        String year;
        String front;
    }

    /** The book file, and whether the work was longer than a book may be and only its beginning is in. */
    static final class Made {
        File file;
        boolean cut;
        /** Parts the library did not give; the book is on the shelf without them. */
        int missing;
    }

    /** Told how far the putting together has come; asked whether to go on. */
    interface Progress {
        void step(int done, int total);

        boolean stopped();
    }

    /** Thrown when the person stopped the work. */
    static final class Stopped extends IOException {
        Stopped() {
            super("stopped");
        }
    }

    static String edition(String query) {
        return Wiki.edition(query);
    }

    /** Works whose title or text matches; parts of works, editions lists and other editions are left out. */
    static List<Found> search(Context c, String query) throws IOException {
        String lang = edition(query);
        // Pages that only list the editions of a work are marked by the library; they are no book.
        JSONObject r = get(c, api(c, lang) + "?action=query&format=json&formatversion=2&generator=search"
                + "&gsrnamespace=0&gsrlimit=40&prop=pageprops&ppprop=disambiguation&gsrsearch="
                + URLEncoder.encode(query, "UTF-8"));
        if (r.has("error")) {
            throw new IOException("library: the search was refused");
        }
        List<Object[]> ranked = new ArrayList<Object[]>();
        try {
            JSONObject q = r.optJSONObject("query");
            JSONArray pages = q == null ? null : q.optJSONArray("pages");
            for (int i = 0; pages != null && i < pages.length(); i++) {
                JSONObject p = pages.getJSONObject(i);
                JSONObject props = p.optJSONObject("pageprops");
                if (props != null && props.has("disambiguation")) {
                    continue;
                }
                ranked.add(new Object[] {p.optInt("index", i + 1000), p.optString("title")});
            }
        } catch (JSONException e) {
            throw new IOException("library: " + e.getMessage());
        }
        Collections.sort(ranked, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return (Integer) a[0] - (Integer) b[0];
            }
        });
        List<String> skip = skip(c);
        List<Found> titled = new ArrayList<Found>();
        List<Found> rest = new ArrayList<Found>();
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (Object[] o : ranked) {
            String page = (String) o[1];
            if (page.isEmpty() || page.indexOf('/') >= 0
                    || skip.contains(added(page).toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            Found f = new Found();
            f.page = page;
            f.lang = lang;
            f.title = shownTitle(page);
            f.by = added(page);
            (f.title.toLowerCase(java.util.Locale.ROOT).contains(q) ? titled : rest).add(f);
        }
        titled.addAll(rest);
        List<Found> out = titled.size() > SHOWN ? new ArrayList<Found>(titled.subList(0, SHOWN)) : titled;
        Diag.mark(c, "library: search, " + out.size() + " shown");
        return out;
    }

    /** The page name without what is added in brackets at its end. */
    static String shownTitle(String page) {
        int open = page.lastIndexOf(" (");
        return open > 0 && page.endsWith(")") ? page.substring(0, open) : page;
    }

    /** What the page name adds in brackets at its end, often the author; "" when nothing. */
    static String added(String page) {
        int open = page.lastIndexOf(" (");
        return open > 0 && page.endsWith(")") ? page.substring(open + 2, page.length() - 1) : "";
    }

    /** Reads the work's front page: its title, author and year as the library gives them. */
    static Work about(Context c, Found f) throws IOException {
        Work w = new Work();
        w.lang = f.lang;
        String[] page = parse(c, f.lang, f.page);
        w.page = page[0];
        w.front = page[1];
        String title = LibraryPage.meta(w.front, "ws-title");
        String author = LibraryPage.meta(w.front, "ws-author");
        w.title = title.isEmpty() ? shownTitle(w.page) : title;
        w.author = author.isEmpty() ? added(w.page) : author;
        w.year = LibraryPage.meta(w.front, "ws-year");
        return w;
    }

    /** Puts the work together and writes it as one book file. */
    static Made assemble(Context c, Work w, Progress p) throws IOException {
        List<String> skip = skip(c);
        Gathering b = new Gathering(c, w, skip, p);
        List<LibraryPage.Link> parts = LibraryPage.parts(w.front, w.page, skip);
        b.seen.add(w.page);
        for (LibraryPage.Link l : parts) {
            b.seen.add(l.page);
        }
        List<String> front = own(LibraryPage.paragraphs(w.front, false), parts);
        if (parts.isEmpty()) {
            b.add("", front);
        } else {
            // A front page with a text of its own (an epigraph, a preface) keeps it before the parts.
            if (chars(front) > FRONT) {
                b.add("", front);
            }
            b.total = parts.size();
            b.expand(parts, 1);
            if (b.failed > Math.max(FAILS, b.pages / 4)) {
                throw new IOException("library: too many parts failed");
            }
        }
        if (b.parts.isEmpty() || b.chars == 0) {
            throw new IOException("library: no text");
        }
        File dir = new File(c.getCacheDir(), "library");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("library: no room");
        }
        // The page's own name: two translations of one title by one author are two pages, two files.
        File out = new File(dir, fileName(w.page) + ".fb2");
        try (Writer o = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"))) {
            LibraryPage.fb2(o, w.title, w.author, w.lang, b.names, b.parts);
        }
        Diag.mark(c, "library: book put together, " + b.parts.size() + " parts, " + b.pages + " pages, "
                + b.chars / 1000 + " thousand letters" + (b.failed > 0 ? ", " + b.failed + " parts missing" : "")
                + (b.cut ? ", cut at the limit" : ""));
        Made m = new Made();
        m.file = out;
        m.cut = b.cut;
        m.missing = b.failed;
        return m;
    }

    /** The parts gathered so far and what is left to fetch. */
    private static final class Gathering {
        final Context c;
        final Work w;
        final List<String> skip;
        final Progress p;
        final Set<String> seen = new HashSet<String>();
        final List<String> names = new ArrayList<String>();
        final List<List<String>> parts = new ArrayList<List<String>>();
        int pages;
        int done;
        int total;
        int chars;
        int failed;
        int inARow;
        boolean cut;

        Gathering(Context c, Work w, List<String> skip, Progress p) {
            this.c = c;
            this.w = w;
            this.skip = skip;
            this.p = p;
        }

        void add(String name, List<String> text) {
            if (text.isEmpty()) {
                return;
            }
            names.add(name);
            parts.add(text);
            chars += chars(text);
        }

        void expand(List<LibraryPage.Link> links, int depth) throws IOException {
            for (LibraryPage.Link l : links) {
                if (p.stopped()) {
                    throw new Stopped();
                }
                if (pages >= MAX_PAGES || chars >= MAX_CHARS) {
                    cut = true;
                    return;
                }
                String[] page = fetch(l.page);
                pages++;
                done++;
                if (page == null) {
                    p.step(done, total);
                    continue;
                }
                // After a redirect the parts live under the page's new name.
                seen.add(page[0]);
                List<LibraryPage.Link> all = LibraryPage.parts(page[1], page[0], skip);
                List<LibraryPage.Link> deeper = new ArrayList<LibraryPage.Link>();
                for (LibraryPage.Link d : all) {
                    if (seen.add(d.page)) {
                        deeper.add(d);
                    }
                }
                List<String> text = own(LibraryPage.paragraphs(page[1], true), all);
                if (!deeper.isEmpty() && chars(text) < CONTENTS && depth < DEPTH) {
                    // A part that is itself a table of contents: its own parts take its place.
                    total += deeper.size();
                    p.step(done, total);
                    expand(deeper, depth + 1);
                    continue;
                }
                p.step(done, total);
                if (!all.isEmpty() && deeper.isEmpty() && chars(text) < SCRAP) {
                    // The contents of a part whose chapters are already in the book: nothing new.
                    continue;
                }
                String name = l.words.isEmpty() ? l.page.substring(l.page.lastIndexOf('/') + 1) : l.words;
                add(name, text);
            }
        }

        /** A page, tried twice; null when it cannot be had, and the book goes on without it. */
        private String[] fetch(String page) throws IOException {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String[] got = parse(c, w.lang, page);
                    inARow = 0;
                    return got;
                } catch (IOException e) {
                    if (p.stopped()) {
                        throw new Stopped();
                    }
                    if (attempt == 0) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            throw new Stopped();
                        }
                    } else {
                        Diag.log(c, "library: a part could not be had", e);
                    }
                }
            }
            failed++;
            if (++inARow >= FAILS_IN_A_ROW || failed > Math.max(FAILS, pages / 4)) {
                throw new IOException("library: the library stopped answering");
            }
            return null;
        }
    }

    /** The page's text without the lines that are only the names of its parts (its contents). */
    private static List<String> own(List<String> text, List<LibraryPage.Link> parts) {
        if (parts.isEmpty()) {
            return text;
        }
        Set<String> names = new HashSet<String>();
        for (LibraryPage.Link l : parts) {
            names.add(l.words);
        }
        List<String> out = new ArrayList<String>(text.size());
        for (String line : text) {
            if (!names.contains(line) && !nameWithTail(line, names)) {
                out.add(line);
            }
        }
        return out;
    }

    /** A contents line: a part's name and nothing but a page number or punctuation after it. */
    private static boolean nameWithTail(String line, Set<String> names) {
        for (String n : names) {
            if (!n.isEmpty() && line.startsWith(n) && TAIL.matcher(line.substring(n.length())).matches()) {
                return true;
            }
        }
        return false;
    }

    private static int chars(List<String> text) {
        int n = 0;
        for (String s : text) {
            n += s.length();
        }
        return n;
    }

    private static String fileName(String title) {
        String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t.isEmpty() ? "book" : t;
    }

    private static List<String> skip(Context c) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String s : lines(c, "talk/library-skip.txt")) {
            out.add(s.toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    /** A page rendered by the library: its name after redirects, and its html. */
    private static String[] parse(Context c, String lang, String page) throws IOException {
        JSONObject r = get(c, api(c, lang) + "?action=parse&format=json&formatversion=2&redirects=1&prop=text"
                + "&disableeditsection=1&disablelimitreport=1&page=" + URLEncoder.encode(page, "UTF-8"));
        JSONObject parse = r.optJSONObject("parse");
        if (parse == null) {
            throw new IOException("library: page not given");
        }
        return new String[] {parse.optString("title", page), parse.optString("text", "")};
    }

    private static String api(Context c, String lang) throws IOException {
        return String.format(lines(c, "talk/library-url.txt").get(0), lang);
    }

    private static JSONObject get(Context c, String address) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(10000);
        // A long chapter not yet in the library's cache is rendered on asking, which takes a while.
        h.setReadTimeout(60000);
        // The library asks every program to say who it is.
        h.setRequestProperty("User-Agent", lines(c, "talk/wiki-agent.txt").get(0));
        try {
            if (h.getResponseCode() != 200) {
                throw new IOException("library: http " + h.getResponseCode());
            }
            return new JSONObject(new String(LanguagePack.readAll(h.getInputStream()), "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("library: " + e.getMessage());
        } finally {
            h.disconnect();
        }
    }

    private static List<String> lines(Context c, String name) throws IOException {
        List<String> out = new ArrayList<String>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(name), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }
}
