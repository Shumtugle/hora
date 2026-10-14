package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one rendered page of the free library: what the page says about the work (title,
 * author, year), which of its links lead to the work's own parts, and the work's text as
 * paragraphs. The library marks for export what belongs to the text and what does not
 * (headers, page numbers, notes, navigation), and those marks are followed here. Plain Java,
 * no network: the pages are fetched elsewhere.
 */
final class LibraryPage {
    private static final Pattern TOKEN = Pattern.compile(
            "(?s)<!--.*?-->|<![^>]*>|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern CLASS = Pattern.compile("(?i)\\bclass\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern ID = Pattern.compile("(?i)\\bid\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE = Pattern.compile("(?i)\\btitle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r\\u00a0\\u2009\\u200b]+");

    /** Elements that hold no text and have no end. */
    private static final String VOID = " area base br col embed hr img input link meta param source track wbr ";
    /** Elements that end a line of text. */
    private static final String BLOCK = " p div br li h1 h2 h3 h4 h5 h6 tr dd dt blockquote center pre table ul ol dl ";
    /** Elements whose content is never text. */
    private static final String NEVER = " script style head noscript math ";
    /** Classes the library gives to what is not the work's text. */
    private static final String[] NOT_TEXT = {"ws-noexport", "noprint", "ws-pagenum", "pagenum", "reference",
        "references", "mw-references-wrap", "mw-editsection", "editsection", "toc", "navbox", "metadata",
        "magnify", "mw-cite-backlink", "headertemplate", "mediaContainer", "licenseContainer", "licensetpl",
        "licenseBanner", "ws-summary"};
    private static final Pattern HIDDEN = Pattern.compile("(?i)\\bstyle\\s*=\\s*[\"'][^\"']*display\\s*:\\s*none");
    /** On a part of a work, what is marked for the work's front page only. */
    private static final String SUBPAGE_NOT_TEXT = "ws-if-subpage-noexport";

    private LibraryPage() {
    }

    /** A link on the page: where it leads and its words. */
    static final class Link {
        final String page;
        final String words;

        Link(String page, String words) {
            this.page = page;
            this.words = words;
        }
    }

    /** The text of the first element with this id or class (ws-author, ws-year...), or "". */
    static String meta(String html, String name) {
        Matcher m = TOKEN.matcher(html);
        while (m.find()) {
            if (m.group(2) == null || !m.group(1).isEmpty()) {
                continue;
            }
            String attrs = m.group(3);
            if (!name.equals(attr(ID, attrs)) && !hasClass(attrs, name)) {
                continue;
            }
            int end = closing(html, m.end(), m.group(2).toLowerCase(Locale.ROOT));
            return clean(BookText.entities(html.substring(m.end(), end).replaceAll("(?s)<[^>]*>", " ")));
        }
        return "";
    }

    /**
     * The work's own parts, in the order of the page: the links of its table of contents when the
     * page has one, otherwise every link to a page under this one. Red links, links out of the
     * library and parts whose name says they are another edition are left out.
     *
     * @param skip names of last parts that mean another edition of the same text, lower case
     */
    static List<Link> parts(String html, String page, List<String> skip) {
        String scope = html;
        Matcher m = TOKEN.matcher(html);
        while (m.find()) {
            if (m.group(2) == null || !m.group(1).isEmpty()) {
                continue;
            }
            String attrs = m.group(3);
            if ("ws-summary".equals(attr(ID, attrs)) || hasClass(attrs, "ws-summary")) {
                scope = html.substring(m.end(), closing(html, m.end(), m.group(2).toLowerCase(Locale.ROOT)));
                break;
            }
        }
        boolean summary = scope != html;
        Map<String, Link> found = new LinkedHashMap<String, Link>();
        String under = page + "/";
        Matcher a = Pattern.compile("(?is)<a\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>(.*?)</a>").matcher(scope);
        while (a.find()) {
            String attrs = a.group(1);
            String href = attr(HREF, attrs);
            String title = BookText.entities(attr(TITLE, attrs));
            if (href.isEmpty() || title.isEmpty() || href.contains("action=edit") || href.contains("redlink=1")
                    || hasClass(attrs, "new") || hasClass(attrs, "extiw") || hasClass(attrs, "external")
                    || hasClass(attrs, "image") || href.startsWith("http") || href.startsWith("//")) {
                continue;
            }
            if (!summary && !title.startsWith(under)) {
                continue;
            }
            if (otherEdition(title, page, skip)) {
                continue;
            }
            // A link into another namespace (an author's page, a category) is not a part of the work.
            if (title.indexOf(':') >= 0 && !title.startsWith(under)) {
                continue;
            }
            if (title.equals(page) || found.containsKey(title)) {
                continue;
            }
            String words = BookText.entities(a.group(2).replaceAll("(?s)<[^>]*>", " "));
            found.put(title, new Link(title, clean(words.replace('\n', ' '))));
        }
        return new ArrayList<Link>(found.values());
    }

    /** Whether any step of the path below the work names another edition (an old spelling). */
    private static boolean otherEdition(String title, String page, List<String> skip) {
        String below = title.startsWith(page + "/") ? title.substring(page.length() + 1) : title;
        for (String step : below.split("/")) {
            if (skip.contains(step.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The work's text on this page, one paragraph per line, without what the library marks as not text. */
    static List<String> paragraphs(String html, boolean subpage) {
        StringBuilder out = new StringBuilder(html.length() / 2);
        Matcher m = TOKEN.matcher(html);
        int at = 0;
        String skipping = null;
        int depth = 0;
        while (m.find()) {
            if (skipping == null) {
                out.append(html, at, m.start());
            }
            at = m.end();
            String tag = m.group(2);
            if (tag == null) {
                continue;
            }
            tag = tag.toLowerCase(Locale.ROOT);
            boolean closing = !m.group(1).isEmpty();
            boolean empty = VOID.contains(" " + tag + " ") || m.group(3).trim().endsWith("/");
            if (skipping != null) {
                if (tag.equals(skipping) && !empty) {
                    depth += closing ? -1 : 1;
                    if (depth == 0) {
                        skipping = null;
                    }
                }
                continue;
            }
            if (!closing && !empty && skipped(tag, m.group(3), subpage)) {
                skipping = tag;
                depth = 1;
                continue;
            }
            if (BLOCK.contains(" " + tag + " ")) {
                out.append('\n');
            } else if (tag.equals("td") || tag.equals("th")) {
                out.append(' ');
            }
        }
        if (skipping == null) {
            out.append(html, at, html.length());
        }
        List<String> lines = new ArrayList<String>();
        for (String line : BookText.entities(out.toString()).split("\n")) {
            String t = clean(line);
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        return lines;
    }

    private static boolean skipped(String tag, String attrs, boolean subpage) {
        if (NEVER.contains(" " + tag + " ")) {
            return true;
        }
        String id = attr(ID, attrs);
        if (id.equals("toc") || id.equals("ws-data") || id.equals("headerContainer") || id.equals("ws-summary")) {
            return true;
        }
        // Hidden on the page is hidden from the book too: data for programs, not for readers.
        if (HIDDEN.matcher(attrs).find()) {
            return true;
        }
        for (String c : NOT_TEXT) {
            if (hasClass(attrs, c)) {
                return true;
            }
        }
        return subpage && hasClass(attrs, SUBPAGE_NOT_TEXT);
    }

    /** Where the element opened just before {@code from} ends, counting the same element nested in it. */
    private static int closing(String html, int from, String tag) {
        Matcher m = TOKEN.matcher(html);
        m.region(from, html.length());
        int depth = 1;
        while (m.find()) {
            if (m.group(2) == null || !m.group(2).equalsIgnoreCase(tag) || m.group(3).trim().endsWith("/")) {
                continue;
            }
            depth += m.group(1).isEmpty() ? 1 : -1;
            if (depth == 0) {
                return m.start();
            }
        }
        return html.length();
    }

    private static String attr(Pattern p, String attrs) {
        Matcher m = p.matcher(attrs);
        if (!m.find()) {
            return "";
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static boolean hasClass(String attrs, String name) {
        String c = attr(CLASS, attrs);
        return !c.isEmpty() && (" " + c.trim() + " ").contains(" " + name + " ");
    }

    private static String clean(String s) {
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /**
     * A book file for the shelf: title and author up front, then each part as a section with its
     * name as the section's title. A part without a name goes in untitled.
     */
    static void fb2(java.io.Writer b, String title, String author, String lang, List<String> names,
            List<List<String>> parts) throws java.io.IOException {
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<FictionBook>\n<description><title-info>\n");
        String a = author == null ? "" : author.trim();
        int cut = a.lastIndexOf(' ');
        b.append("<author><first-name>").append(xml(cut > 0 ? a.substring(0, cut) : ""))
                .append("</first-name><last-name>").append(xml(cut > 0 ? a.substring(cut + 1) : a))
                .append("</last-name></author>\n");
        b.append("<book-title>").append(xml(title)).append("</book-title>\n");
        b.append("<lang>").append(xml(lang)).append("</lang>\n");
        b.append("</title-info></description>\n<body>\n");
        for (int i = 0; i < parts.size(); i++) {
            b.append("<section>\n");
            String name = i < names.size() ? names.get(i) : "";
            if (name != null && !name.trim().isEmpty()) {
                b.append("<title><p>").append(xml(name.trim())).append("</p></title>\n");
            }
            for (String p : parts.get(i)) {
                b.append("<p>").append(xml(p)).append("</p>\n");
            }
            b.append("</section>\n");
        }
        b.append("</body>\n</FictionBook>\n");
    }

    private static String xml(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                default:
                    // Control characters other than tab and line ends are not allowed in the file.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
