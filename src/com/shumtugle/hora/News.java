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
 * The paper on the Day: headlines from the feeds a person switched on, at most
 * eight at once, newest first. Titles only, never the stories, each feed asked
 * at most every half hour and only while the Day is open; shown only when the
 * developer room has the paper switched on. The first feeds and the catalogue
 * of rubrics live in the bundle; a feed's name comes from the feed itself.
 */
final class News {
    private static final String PREFS = "news";
    private static final String ON = "on";
    /** The broadcaster chosen before the paper had several; read once to seed the choice. */
    private static final String OLD_SOURCE = "source";
    private static final String CHOSEN = "chosen";
    private static final String NAMES = "names";
    private static final long FRESH_MS = 30 * 60 * 1000L;
    /** A feed that did not answer is asked again sooner than one that did. */
    private static final long RETRY_MS = 5 * 60 * 1000L;
    /** Headlines without a date of their own stand below the last hours of dated ones, in their feed's order. */
    private static final long UNDATED_MS = 3 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** How many headlines are taken from one feed; the newest come first in every feed. */
    static final int MAX = 15;
    /** How many feeds the paper takes at once: more would mean long waits and many askings. */
    static final int MAX_CHOSEN = 8;
    /** How many headlines the paper shows together. */
    static final int MAX_SHOWN = 30;
    /** A feed's name is cut to this. */
    private static final int MAX_NAME = 60;

    /** A feed: its address and the language its headlines are in. */
    static final class Feed {
        final String url;
        final String lang;

        Feed(String url, String lang) {
            this.url = url;
            this.lang = lang;
        }
    }

    /** One of the first feeds: a feed with a name of its own before it ever answered. */
    static final class Starter {
        final String key;
        final Feed feed;
        final String name;

        Starter(String key, Feed feed, String name) {
            this.key = key;
            this.feed = feed;
            this.name = name;
        }
    }

    /** A rubric of the catalogue: its key, language, names in the two screen languages, and feeds. */
    static final class Rubric {
        final String key;
        final String lang;
        final String nameRu;
        final String nameEn;
        final List<String> feeds = new ArrayList<String>();

        Rubric(String key, String lang, String nameRu, String nameEn) {
            this.key = key;
            this.lang = lang;
            this.nameRu = nameRu;
            this.nameEn = nameEn;
        }
    }

    /** A headline: its words, the feed it came from, when it was published (or placed), and its language. */
    static final class Item {
        final String title;
        final String feed;
        final long at;
        final String lang;

        /** Whether the feed gave the date; without one, no time is shown. */
        final boolean dated;

        Item(String title, String feed, long at, String lang, boolean dated) {
            this.title = title;
            this.feed = feed;
            this.at = at;
            this.lang = lang;
            this.dated = dated;
        }

        /** What the headline is, for telling a paper drawn before from the same paper fetched again. */
        String key() {
            return feed + '\n' + title + '\n' + at;
        }
    }

    /** What one feed gave last: when it was asked, its headlines, and whether that asking failed. */
    private static final class Got {
        final long at;
        final List<Item> items;
        final boolean failed;

        Got(long at, List<Item> items, boolean failed) {
            this.at = at;
            this.items = items;
            this.failed = failed;
        }
    }

    /** The paper as one snapshot: its headlines, newest first, and how many feeds gave them. */
    static final class Paper {
        final List<Item> items;
        final int sources;

        Paper(List<Item> items, int sources) {
            this.items = Collections.unmodifiableList(items);
            this.sources = sources;
        }
    }

    /** A feed read: its own title, and its items' titles with their dates (0 when a date does not read). */
    static final class Parsed {
        String channel = "";
        final List<String> titles = new ArrayList<String>();
        final List<Long> dates = new ArrayList<Long>();
    }

    private static final java.util.Map<String, Got> GOT = new java.util.concurrent.ConcurrentHashMap<String, Got>();
    private static volatile List<Starter> starters;
    private static volatile List<Rubric> catalog;

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

    /** Whether headlines in this language can be read aloud: Russian always, English with its voice. */
    static boolean readable(Context c, String lang) {
        return "ru".equals(lang) || ("en".equals(lang) && english(c));
    }

    private static volatile boolean englishKnown;
    private static volatile long englishAt;

    /** Whether the English voice is there; looked at on the disk at most every ten seconds. */
    private static boolean english(Context c) {
        long now = System.currentTimeMillis();
        if (now < englishAt || now - englishAt > 10000) {
            englishKnown = LanguagePack.installed(c, LanguagePack.ENGLISH);
            englishAt = now;
        }
        return englishKnown;
    }

    // ---- The first feeds and the catalogue ----

    /** The first feeds, from the bundle: key|name|address|language (Russian when no language is given). */
    static List<Starter> starters(Context c) {
        List<Starter> s = starters;
        if (s != null) {
            return s;
        }
        List<Starter> found = new ArrayList<Starter>();
        try {
            for (String line : readAll(c.getAssets().open("talk/news.txt"), "UTF-8", 0).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\|");
                if (p.length >= 3) {
                    found.add(new Starter(p[0].trim(), new Feed(p[2].trim(), p.length >= 4 ? p[3].trim() : "ru"), p[1].trim()));
                }
            }
        } catch (IOException e) {
            Diag.log(c, "news: the first feeds could not be read", e);
        }
        starters = Collections.unmodifiableList(found);
        return starters;
    }

    /** The rubrics of the catalogue, Russian first; read once and kept while the app runs. */
    static List<Rubric> catalog(Context c) {
        List<Rubric> got = catalog;
        if (got != null) {
            return got;
        }
        List<Rubric> found = new ArrayList<Rubric>();
        try {
            Rubric cur = null;
            for (String line : readAll(c.getAssets().open("talk/news-catalog.txt"), "UTF-8", 0).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("=")) {
                    String[] p = line.substring(1).split("\\|");
                    cur = p.length >= 4 ? new Rubric(p[0], p[1], p[2], p[3]) : null;
                    if (cur != null) {
                        found.add(cur);
                    }
                } else if (cur != null && line.startsWith("https:")) {
                    // Only feeds asked over a secure connection: the phone refuses the others anyway.
                    cur.feeds.add(line);
                }
            }
        } catch (IOException e) {
            Diag.log(c, "news: the catalogue could not be read", e);
        }
        catalog = Collections.unmodifiableList(found);
        return catalog;
    }

    static Rubric rubric(Context c, String key) {
        for (Rubric r : catalog(c)) {
            if (r.key.equals(key)) {
                return r;
            }
        }
        return null;
    }

    // ---- What is chosen ----

    /** The feeds switched on, in the order they were; the first start has the first feed, or the one chosen before. */
    static List<Feed> chosen(Context c) {
        List<Feed> out = new ArrayList<Feed>();
        String raw = sp(c).getString(CHOSEN, null);
        if (raw == null) {
            List<Starter> first = starters(c);
            String old = sp(c).getString(OLD_SOURCE, "");
            Starter pick = first.isEmpty() ? null : first.get(0);
            for (Starter s : first) {
                if (s.key.equals(old)) {
                    pick = s;
                }
            }
            if (pick != null) {
                out.add(pick.feed);
            }
            save(c, out);
            return out;
        }
        Object[] memo = chosenMemo;
        if (memo != null && raw.equals(memo[0])) {
            @SuppressWarnings("unchecked")
            List<Feed> same = (List<Feed>) memo[1];
            return new ArrayList<Feed>(same);
        }
        try {
            org.json.JSONArray a = new org.json.JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new Feed(o.getString("u"), o.optString("l", "ru")));
            }
        } catch (org.json.JSONException e) {
            Diag.log(c, "news: the choice could not be read", e);
        }
        chosenMemo = new Object[] {raw, Collections.unmodifiableList(new ArrayList<Feed>(out))};
        return out;
    }

    /** The choice as last read, with the text it was read from: read again only when the text changed. */
    private static volatile Object[] chosenMemo;

    /** The chosen feeds whose language can be read now. */
    static List<Feed> active(Context c) {
        List<Feed> out = new ArrayList<Feed>();
        for (Feed f : chosen(c)) {
            if (readable(c, f.lang) && out.size() < MAX_CHOSEN) {
                out.add(f);
            }
        }
        return out;
    }

    static boolean isChosen(Context c, String url) {
        for (Feed f : chosen(c)) {
            if (f.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    /** Switches a feed on or off; false when switching on would pass the limit, and nothing changes then. */
    static boolean choose(Context c, Feed feed, boolean on) {
        List<Feed> all = chosen(c);
        List<Feed> next = new ArrayList<Feed>();
        int readableOthers = 0;
        for (Feed f : all) {
            if (!f.url.equals(feed.url)) {
                next.add(f);
                if (readable(c, f.lang)) {
                    readableOthers++;
                }
            }
        }
        if (on) {
            // The limit counts what the paper takes now; a feed in a language that cannot be read waits aside.
            if (readableOthers >= MAX_CHOSEN) {
                return false;
            }
            next.add(feed);
        }
        save(c, next);
        return true;
    }

    private static void save(Context c, List<Feed> feeds) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Feed f : feeds) {
                a.put(new org.json.JSONObject().put("u", f.url).put("l", f.lang));
            }
        } catch (org.json.JSONException e) {
            return;
        }
        sp(c).edit().putString(CHOSEN, a.toString()).apply();
    }

    /** What the choice is, as one line: a paper drawn for another choice is drawn anew. */
    static String signature(Context c) {
        StringBuilder b = new StringBuilder();
        for (Feed f : active(c)) {
            b.append(f.url).append('\n');
        }
        return b.toString();
    }

    // ---- Names ----

    /** The feed's name: the one it gave itself, or the first feeds' own, or the site's address. */
    static String name(Context c, String url) {
        String learned = learned(c, url);
        if (learned != null) {
            return learned;
        }
        for (Starter s : starters(c)) {
            if (s.feed.url.equals(url)) {
                return s.name;
            }
        }
        return host(url);
    }

    /** The name the feed gave itself when it answered; null until it did. */
    static String learned(Context c, String url) {
        String n = names(c).get(url);
        return n == null || n.isEmpty() ? null : n;
    }

    /** The names feeds gave themselves, read once and kept; changed only by learn(). */
    private static volatile java.util.Map<String, String> names;
    /** Grows with every name learned, so a paper drawn with an older name is drawn again. */
    private static volatile int namesVersion;
    /** Its own lock: names are learned by the askings while the paper waits for them on the paper's lock. */
    private static final Object NAMES_LOCK = new Object();

    static int namesVersion() {
        return namesVersion;
    }

    private static java.util.Map<String, String> names(Context c) {
        java.util.Map<String, String> m = names;
        if (m != null) {
            return m;
        }
        synchronized (NAMES_LOCK) {
            if (names == null) {
                java.util.Map<String, String> read = new java.util.HashMap<String, String>();
                try {
                    org.json.JSONObject o = new org.json.JSONObject(sp(c).getString(NAMES, "{}"));
                    java.util.Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        read.put(k, o.optString(k, ""));
                    }
                } catch (org.json.JSONException e) {
                    // Without the names the sites' addresses stand in.
                }
                names = Collections.unmodifiableMap(read);
            }
            return names;
        }
    }

    private static void learn(Context c, String url, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        String cut = name.codePointCount(0, name.length()) > MAX_NAME
                ? name.substring(0, name.offsetByCodePoints(0, MAX_NAME)).trim() + "\u2026" : name;
        synchronized (NAMES_LOCK) {
            java.util.Map<String, String> m = new java.util.HashMap<String, String>(names(c));
            if (cut.equals(m.get(url))) {
                return;
            }
            m.put(url, cut);
            names = Collections.unmodifiableMap(m);
            namesVersion++;
            sp(c).edit().putString(NAMES, new org.json.JSONObject(m).toString()).apply();
        }
    }

    /** The site's own name, without its "www.", for any address on it. */
    static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (java.net.MalformedURLException e) {
            return url;
        }
    }

    // ---- The paper ----

    /** Whether any feed switched on has nothing fresh: then the Day asks. */
    static boolean due(Context c) {
        long now = System.currentTimeMillis();
        for (Feed f : active(c)) {
            if (stale(GOT.get(f.url), now, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a feed is to be asked: never asked, half an hour since an answer, five minutes since a
     * failure, or at once after a failure when a person asks again.
     */
    private static boolean stale(Got g, long now, boolean again) {
        if (g == null || now < g.at) {
            return true;
        }
        if (g.failed) {
            return again || now - g.at >= RETRY_MS;
        }
        return now - g.at >= FRESH_MS;
    }

    /** The paper from what was asked lately, without the network; null when no feed switched on has given anything yet. */
    static Paper paper(Context c) {
        List<Item> all = new ArrayList<Item>();
        boolean any = false;
        for (Feed f : active(c)) {
            Got g = GOT.get(f.url);
            if (g == null) {
                continue;
            }
            any = true;
            all.addAll(g.items);
        }
        if (!any) {
            return null;
        }
        Collections.sort(all, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.at == b.at ? 0 : a.at > b.at ? -1 : 1;
            }
        });
        List<Item> shown = new ArrayList<Item>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.Set<String> from = new java.util.HashSet<String>();
        for (Item it : all) {
            if (shown.size() >= MAX_SHOWN) {
                break;
            }
            if (seen.add(it.title.toLowerCase(java.util.Locale.ROOT))) {
                shown.add(it);
                from.add(it.feed);
            }
        }
        // The count is of the feeds the shown headlines came from, not of all that answered.
        return new Paper(shown, from.size());
    }

    /**
     * Asks every feed switched on that has nothing fresh, three at a time, and returns the paper.
     * A feed that fails keeps what it gave before. Off the main thread.
     */
    static synchronized Paper fetch(final Context c, boolean again) {
        final long now = System.currentTimeMillis();
        List<Feed> ask = new ArrayList<Feed>();
        java.util.Set<String> wanted = new java.util.HashSet<String>();
        for (Feed f : active(c)) {
            wanted.add(f.url);
            if (stale(GOT.get(f.url), now, again)) {
                ask.add(f);
            }
        }
        // Feeds switched off let go of what they gave.
        GOT.keySet().retainAll(wanted);
        if (!ask.isEmpty()) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(3, ask.size()));
            for (final Feed f : ask) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        one(c, f);
                    }
                });
            }
            pool.shutdown();
            try {
                // Each asking has its own deadline; this only keeps a stuck one from holding the paper forever.
                pool.awaitTermination(DEADLINE_MS * 3 + 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return paper(c);
    }

    private static void one(Context c, Feed f) {
        long now = System.currentTimeMillis();
        String host = host(f.url);
        Got before = GOT.get(f.url);
        try {
            Parsed p = read(get(c, f.url));
            if (p.titles.isEmpty()) {
                throw new IOException("no headlines");
            }
            List<Item> items = new ArrayList<Item>();
            for (int i = 0; i < p.titles.size(); i++) {
                long at = p.dates.get(i);
                boolean dated = at > 0 && at <= now + 60 * 60 * 1000L;
                // Without a readable date the headline keeps its feed's order, below the last hours of dated ones.
                items.add(new Item(p.titles.get(i), f.url, dated ? at : now - UNDATED_MS - (i + 1) * 60 * 1000L,
                        f.lang, dated));
            }
            learn(c, f.url, p.channel);
            GOT.put(f.url, new Got(now, items, false));
            // How many and from where; the headlines themselves stay out of the journal.
            Diag.log(c, "news: " + items.size() + " headlines from " + host);
        } catch (Exception e) {
            Diag.log(c, "news: no answer from " + host + ", " + e.getClass().getSimpleName());
            GOT.put(f.url, new Got(now, before == null ? new ArrayList<Item>() : before.items, true));
        }
    }

    // ---- Reading a feed ----

    /** How many items a feed may offer before the rest is left unread, titled or not. */
    private static final int MAX_ITEMS = 300;
    /** A title longer than this is no headline; it is cut before it is read. */
    private static final int MAX_TITLE = 2000;

    /** The titles of a feed's items, as read(). */
    static List<String> parse(String xml) {
        return read(xml).titles;
    }

    /**
     * A feed read in one pass, without a full XML reader: its own title, and each item's or entry's
     * first title with its date, newest first as the feed has them; markup and entities undone,
     * spaces made single. What cannot be read is dropped.
     */
    static Parsed read(String xml) {
        Parsed out = new Parsed();
        if (xml == null) {
            return out;
        }
        xml = withoutComments(xml);
        int item = nextOpen(xml, 0, xml.length(), "item");
        int entry = nextOpen(xml, 0, xml.length(), "entry");
        int first = item < 0 ? entry : entry < 0 ? item : Math.min(item, entry);
        String channel = firstTitle(xml, 0, first < 0 ? xml.length() : first);
        out.channel = channel == null ? "" : channel;
        int at = 0;
        int seen = 0;
        while (out.titles.size() < MAX && seen < MAX_ITEMS) {
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
            if (title != null && !title.isEmpty() && !out.titles.contains(title)) {
                out.titles.add(title);
                out.dates.add(dateOf(xml, start, stop));
            }
            at = end < 0 ? xml.length() : end + tag.length() + 2;
        }
        return out;
    }

    private static final String[] DATE_TAGS = {"pubDate", "published", "updated", "dc:date"};

    /** When the item was published, in milliseconds; 0 when no date reads. */
    private static long dateOf(String xml, int from, int to) {
        String body = xml.substring(from, Math.min(to, xml.length()));
        for (String tag : DATE_TAGS) {
            int open = nextOpen(body, 0, body.length(), tag);
            if (open < 0) {
                continue;
            }
            int gt = body.indexOf('>', open);
            int close = gt < 0 ? -1 : body.indexOf("</" + tag, gt);
            if (close < 0 || close - gt > 80) {
                continue;
            }
            long t = parseDate(clean(body.substring(gt + 1, close)));
            if (t > 0) {
                return t;
            }
        }
        return 0;
    }

    private static final String[] RFC822 = {
            "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss z", "d MMM yyyy HH:mm:ss Z",
            "d MMM yyyy HH:mm:ss z", "EEE, d MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm z",
    };

    /** A date as feeds write it: the mail form ("Fri, 16 Oct 2026 20:15:00 +0300") or the ISO one; 0 when it does not read. */
    static long parseDate(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        s = s.trim();
        if (s.length() >= 19 && Character.isDigit(s.charAt(0)) && s.charAt(4) == '-' && s.charAt(10) == 'T') {
            // ISO: drop a fraction of a second, read "Z" as UTC.
            String t = s.replaceFirst("\\.\\d+", "");
            if (t.endsWith("Z")) {
                t = t.substring(0, t.length() - 1) + "+00:00";
            }
            if (t.length() == 19) {
                t = t + "+00:00";
            }
            if (t.length() >= 25 && t.charAt(22) == ':') {
                t = t.substring(0, 22) + t.substring(23);
            }
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT);
                f.setLenient(false);
                return f.parse(t).getTime();
            } catch (java.text.ParseException e) {
                return 0;
            }
        }
        for (String pattern : RFC822) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                f.setLenient(false);
                return f.parse(s).getTime();
            } catch (java.text.ParseException e) {
                // The next form.
            }
        }
        // A weekday that does not match the date is a slip of the feed; the date itself still stands.
        if (s.length() > 5 && s.charAt(3) == ',' && Character.isLetter(s.charAt(0))) {
            return parseDate(s.substring(4).trim());
        }
        return 0;
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
