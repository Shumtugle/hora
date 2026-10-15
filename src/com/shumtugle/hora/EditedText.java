package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what the library of edited editions publishes about a book and the book itself: the
 * line that names a book in the list of all of them, the package that describes one (title,
 * author, a sentence about it, its genres, its length, the order of its files), one file of
 * its text as paragraphs, and the feed of the latest books. These books are proofread by hand
 * and marked up with care, so the marks are trusted: headings, note numbers and the pages
 * about the edition itself are known by their marks. Plain Java, no network.
 */
final class EditedText {
    private EditedText() {
    }

    /** A book of the library: where it lives, how it is named, and what is known about it so far. */
    static final class Book {
        /** The name of its source, which is also its address in the library with the steps joined. */
        String repo = "";
        /** Its address in the library below the books' root, steps joined by slashes. */
        String page = "";
        String title = "";
        String author = "";
        String translator = "";
        /** From the feed: one sentence about it, its genres, and its small cover's address. */
        String summary = "";
        final List<String> genres = new ArrayList<String>();
        String thumb = "";
    }

    /** What the package of one book says. */
    static final class Package {
        String title = "";
        final List<String> authors = new ArrayList<String>();
        String summary = "";
        final List<String> genres = new ArrayList<String>();
        int words;
        String language = "";
        /** The files of the text in reading order, as paths below the package. */
        final List<String> spine = new ArrayList<String>();

        String author() {
            return joinNames(authors);
        }
    }

    /** Pages about the edition, lists and notes: no part of what is read aloud. */
    private static final String[] NOT_READ = {"titlepage", "halftitlepage", "imprint", "colophon", "copyright-page",
        "endnotes", "footnotes", "rearnotes", "loi", "lot", "toc", "landmarks", "glossary", "index"};

    private static final Pattern TYPE = Pattern.compile("epub:type\\s*=\\s*\"([^\"]*)\"");

    /**
     * A book from its line in the list of all books: the line is a fixed sentence before the
     * title, ", by " and the author, and sometimes a sentence naming the translator. Null when the
     * line is not that sentence (the library's own tools live in the same list).
     *
     * @param lead the fixed words before the title
     * @param translated the words between the author and the translator
     */
    static Book fromLine(String repo, String home, String line, String root, String lead, String translated) {
        if (repo == null || line == null || home == null) {
            return null;
        }
        String l = line.trim();
        String lw = lead.trim();
        int at = home.indexOf(root);
        if (!l.startsWith(lw) || at < 0) {
            return null;
        }
        String page = home.substring(at + root.length());
        while (page.endsWith("/")) {
            page = page.substring(0, page.length() - 1);
        }
        if (page.isEmpty() || page.indexOf('/') < 0) {
            return null;
        }
        String rest = l.substring(lw.length()).trim();
        Book b = new Book();
        b.repo = repo;
        b.page = page;
        String tr = translated.trim();
        int t = tr.isEmpty() ? -1 : rest.indexOf(tr);
        if (t >= 0) {
            b.translator = trimEnd(rest.substring(t + tr.length()).trim());
            rest = rest.substring(0, t).trim();
        }
        rest = trimEnd(rest);
        int by = rest.lastIndexOf(", by ");
        if (by > 0) {
            b.title = rest.substring(0, by).trim();
            b.author = rest.substring(by + 5).trim();
        } else {
            // A book without an author's name (a collection, an anonymous work) is still a book.
            b.title = rest;
        }
        return b.title.isEmpty() ? null : b;
    }

    /** Without a closing full stop; the stop of "Jr.", "Sr.", "St." or an initial stays. */
    private static String trimEnd(String s) {
        String t = s.trim();
        if (t.endsWith(".")) {
            String[] w = t.substring(0, t.length() - 1).split("\\s+");
            String last = w[w.length - 1];
            if (!last.matches("(?i)jr|sr|st|\\p{Lu}")) {
                t = t.substring(0, t.length() - 1).trim();
            }
        }
        return t;
    }

    /** "A", "A and B", "A, B, and C". */
    static String joinNames(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                b.append(i == names.size() - 1 ? ", and " : ", ");
            }
            b.append(names.get(i));
        }
        return b.toString();
    }

    /** Reads a book's package. */
    static Package readPackage(String opf) {
        Package p = new Package();
        p.title = text(first(opf, "<dc:title\\b[^>]*>(.*?)</dc:title>"));
        Matcher a = Pattern.compile("(?s)<dc:creator\\b([^>]*)>(.*?)</dc:creator>").matcher(opf);
        while (a.find()) {
            String id = attr(a.group(1), "id");
            // Only authors: the package names everyone who worked on the book.
            if (id.isEmpty() || id.startsWith("author") || role(opf, id).equals("aut")) {
                String name = text(a.group(2));
                if (!name.isEmpty() && !p.authors.contains(name)) {
                    p.authors.add(name);
                }
            }
        }
        p.summary = text(first(opf, "<meta\\b[^>]*property=\"schema:abstract\"[^>]*>(.*?)</meta>"));
        Matcher g = Pattern.compile("(?s)<meta\\b[^>]*property=\"schema:genre\"[^>]*>(.*?)</meta>").matcher(opf);
        while (g.find()) {
            String genre = text(g.group(1));
            if (!genre.isEmpty() && !p.genres.contains(genre)) {
                p.genres.add(genre);
            }
        }
        String words = text(first(opf, "<meta\\b[^>]*property=\"schema:wordCount\"[^>]*>(.*?)</meta>"));
        p.words = words.matches("\\d{1,9}") ? Integer.parseInt(words) : 0;
        p.language = text(first(opf, "<dc:language\\b[^>]*>(.*?)</dc:language>"));
        java.util.Map<String, String> items = new java.util.HashMap<String, String>();
        Matcher it = Pattern.compile("<item\\b([^>]*)/?>").matcher(opf);
        while (it.find()) {
            items.put(attr(it.group(1), "id"), attr(it.group(1), "href"));
        }
        Matcher sp = Pattern.compile("<itemref\\b([^>]*)/?>").matcher(opf);
        while (sp.find()) {
            String attrs = sp.group(1);
            if ("no".equals(attr(attrs, "linear"))) {
                continue;
            }
            String href = items.get(attr(attrs, "idref"));
            if (href != null && !href.isEmpty() && !p.spine.contains(href)) {
                p.spine.add(href);
            }
        }
        return p;
    }

    /** The role the package gives a person, by the person's mark; "" when it gives none. */
    private static String role(String opf, String id) {
        return text(first(opf, "<meta\\b[^>]*property=\"role\"[^>]*refines=\"#" + Pattern.quote(id)
                + "\"[^>]*>(.*?)</meta>"));
    }

    /** One file of the text, in one part or several. */
    static final class Part {
        /** The name of each part: a chapter's number and title, a story's or a poem's name. */
        final List<String> names = new ArrayList<String>();
        final List<List<String>> texts = new ArrayList<List<String>>();
        /** A page about the edition or a list of notes: left out of the book. */
        boolean skipped;
        /** A heading with no text under it at the file's end (a part's title): it goes before the next file's first part. */
        String carried = "";
    }

    /** The heading lines inside a header: its title group, or its single heading line. */
    private static final Pattern HEADING_LINES = Pattern.compile("(?s)<hgroup\\b.*?</hgroup>|<h([1-6])\\b.*?</h\\1>");

    /** A heading: a group of title lines, a header, or a single heading line. */
    private static final Pattern HEADING = Pattern.compile(
            "(?s)<hgroup\\b.*?</hgroup>|<header\\b.*?</header>|<h([1-6])\\b.*?</h\\1>");

    /**
     * One file of the text as paragraphs. Headings go into the names of the parts, not into the
     * text, so a title is said once. A file with one heading is one part under the file's own
     * name; a file with many (a book of poems, of short pieces) is cut at each, every piece under
     * its heading. Note numbers are left out, and so are the pages about the edition itself.
     */
    static Part readPart(String xhtml) {
        Part out = new Part();
        String head = first(xhtml, "<head\\b[^>]*>(.*?)</head>");
        String own = text(first(head, "<title\\b[^>]*>(.*?)</title>"));
        int open = xhtml.indexOf("<body");
        String body = open < 0 ? xhtml : xhtml.substring(open);
        // The kind of page is marked on the body and its first section.
        Matcher t = TYPE.matcher(body);
        for (int i = 0; i < 2 && t.find(); i++) {
            for (String w : t.group(1).split("\\s+")) {
                for (String n : NOT_READ) {
                    if (w.equals(n)) {
                        out.skipped = true;
                        return out;
                    }
                }
            }
        }
        body = body.replaceAll("(?s)<a\\b[^>]*epub:type=\"[^\"]*noteref[^\"]*\"[^>]*>.*?</a>", "")
                .replaceAll("(?s)<img\\b[^>]*>", "");
        List<String> names = new ArrayList<String>();
        List<String> pieces = new ArrayList<String>();
        Matcher h = HEADING.matcher(body);
        int at = 0;
        String name = "";
        String rest = "";
        while (h.find()) {
            String words = headingText(h.group());
            if (words.isEmpty()) {
                // A header with no heading line in it (a dedication's) is text, not a place to cut.
                continue;
            }
            pieces.add(rest + body.substring(at, h.start()));
            names.add(name);
            name = words;
            // What a header holds besides its heading (an epigraph, a summary) is text of the piece it opens.
            rest = h.group().startsWith("<header") ? HEADING_LINES.matcher(h.group()).replaceAll("\n") : "";
            at = h.end();
        }
        pieces.add(rest + body.substring(at));
        names.add(name);
        int headings = names.size() - 1;
        if (headings <= 1) {
            // One heading or none: the whole file is one part, named as the file names itself.
            StringBuilder all = new StringBuilder();
            for (String piece : pieces) {
                all.append(piece).append('\n');
            }
            String n = own.isEmpty() ? name : own;
            if (!add(out, n, all.toString())) {
                out.carried = n;
            }
            return out;
        }
        String carried = "";
        for (int i = 0; i < pieces.size(); i++) {
            String n = i == 0 ? own : names.get(i);
            if (!carried.isEmpty()) {
                // A heading with no text under it (a part's title) goes before the next one.
                n = joinTitles(carried, n);
                carried = "";
            }
            if (!add(out, n, pieces.get(i)) && i > 0) {
                carried = n;
            }
        }
        out.carried = carried;
        return out;
    }

    /** A part's title before a chapter's, once: "Book the First. I: The Period", never "Act I. Act I". */
    static String joinTitles(String outer, String inner) {
        if (outer.isEmpty()) {
            return inner;
        }
        if (inner.isEmpty() || outer.equalsIgnoreCase(inner)) {
            return outer;
        }
        if (inner.startsWith(outer)) {
            return inner;
        }
        return outer + ". " + inner;
    }

    /** Adds a part if it has text; false when it has none. */
    private static boolean add(Part out, String name, String html) {
        List<String> text = new ArrayList<String>();
        for (String p : LibraryPage.paragraphs(html, true)) {
            String s = tidy(p);
            if (!s.isEmpty()) {
                text.add(s);
            }
        }
        if (text.isEmpty()) {
            return false;
        }
        out.names.add(name);
        out.texts.add(text);
        return true;
    }

    /** A heading's words: its title lines joined, "I: Into the Primitive". */
    private static String headingText(String html) {
        if (html.startsWith("<header")) {
            // A header is named by its title group or its heading line; what else it holds is text.
            Matcher inner = HEADING_LINES.matcher(html);
            return inner.find() ? headingText(inner.group()) : "";
        }
        if (!html.startsWith("<hgroup")) {
            return tidy(text(html));
        }
        StringBuilder b = new StringBuilder();
        Matcher m = Pattern.compile("(?s)<(h[1-6]|p)\\b[^>]*>(.*?)</\\1>").matcher(html);
        while (m.find()) {
            String w = tidy(text(m.group(2)));
            if (!w.isEmpty()) {
                if (b.length() > 0) {
                    b.append(": ");
                }
                b.append(w);
            }
        }
        return b.length() > 0 ? b.toString() : tidy(text(html));
    }

    /**
     * Typesetting the voice does not need: the joiner that keeps a dash with its word goes, a
     * hair space becomes nothing between quotes and a space elsewhere.
     */
    static String tidy(String s) {
        String t = s.replace("\u2060", "").replace("\u00ad", "");
        t = t.replaceAll("(?<=[\u2018\u2019\u201c\u201d])\u200a(?=[\u2018\u2019\u201c\u201d])", "");
        t = t.replace('\u200a', ' ').replace('\u2009', ' ');
        return t.replaceAll(" {2,}", " ").trim();
    }

    /** The latest books, as the feed gives them. */
    static List<Book> readFeed(String xml, String root) {
        List<Book> out = new ArrayList<Book>();
        Matcher e = Pattern.compile("(?s)<entry\\b.*?</entry>").matcher(xml);
        while (e.find()) {
            String entry = e.group();
            String id = text(first(entry, "<id\\b[^>]*>(.*?)</id>"));
            int at = id.indexOf(root);
            if (at < 0) {
                continue;
            }
            Book b = new Book();
            b.page = id.substring(at + root.length()).replaceAll("/+$", "");
            b.repo = b.page.replace('/', '_');
            b.title = text(first(entry, "<title\\b[^>]*>(.*?)</title>"));
            List<String> names = new ArrayList<String>();
            Matcher n = Pattern.compile("(?s)<author\\b.*?<name\\b[^>]*>(.*?)</name>").matcher(entry);
            while (n.find()) {
                names.add(text(n.group(1)));
            }
            b.author = joinNames(names);
            b.summary = text(first(entry, "<summary\\b[^>]*>(.*?)</summary>"));
            Matcher c = Pattern.compile("<category\\b([^>]*)/?>").matcher(entry);
            while (c.find()) {
                String term = BookText.entities(attr(c.group(1), "term")).trim();
                if (!term.isEmpty()) {
                    b.genres.add(term);
                }
            }
            Matcher th = Pattern.compile("<media:thumbnail\\b([^>]*)/?>").matcher(entry);
            if (th.find()) {
                b.thumb = BookText.entities(attr(th.group(1), "url"));
            }
            if (!b.title.isEmpty() && b.page.indexOf('/') > 0) {
                out.add(b);
            }
        }
        return out;
    }

    /** The genres among a book's labels that are in the given list, in the list's order of the labels. */
    static List<String> genres(List<String> labels, java.util.Set<String> known) {
        List<String> out = new ArrayList<String>();
        for (String l : labels) {
            String k = key(l);
            if (known.contains(k) && !out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** A genre's name as a key: lower case, any apostrophe plain. */
    static String key(String genre) {
        return genre.trim().replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    /** The letters of a text without accents, in lower case: a name with accents is found typed without them. */
    static String plain(String s) {
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}+", "").replace('\u2019', '\'').toLowerCase(Locale.ROOT);
    }

    /** The word a name is sorted by: its last, past a closing "Jr." or a numeral. */
    static String sortName(String author) {
        String a = author;
        int and = a.indexOf(" and ");
        if (and > 0) {
            a = a.substring(0, and);
        }
        int comma = a.indexOf(',');
        if (comma > 0) {
            a = a.substring(0, comma);
        }
        String[] w = a.trim().split("\\s+");
        for (int i = w.length - 1; i >= 0; i--) {
            String x = w[i].replace(".", "");
            if (!x.isEmpty() && !x.equalsIgnoreCase("jr") && !x.equalsIgnoreCase("sr") && !x.matches("[IVX]+")) {
                return plain(w[i]);
            }
        }
        return plain(a);
    }

    private static String first(String s, String regex) {
        Matcher m = Pattern.compile("(?s)" + regex).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    /** Text without tags, its entities read, its spaces one. */
    private static String text(String s) {
        return BookText.entities(s.replaceAll("(?s)<[^>]*>", " ")).replaceAll("[\\s\u00a0]+", " ").trim();
    }
}
