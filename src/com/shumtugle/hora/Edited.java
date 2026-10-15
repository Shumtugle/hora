package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library of edited editions: English books whose rights have run out, each proofread by
 * hand against a scan of a real edition, with a painting on its cover. Every book is a source
 * of its own in a public collection; the list of all of them is asked for once a week and kept,
 * so a search needs no network. A book's description, its small cover and the feed of the
 * latest books are asked for when they are shown and kept too. A book is put together from its
 * files one by one into one book file for the shelf, its own cover inside. Never on the main
 * thread, except for what is already kept.
 *
 * The addresses and the fixed words of the list live in the bundle.
 */
final class Edited {
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final long HALF_DAY = 12L * 60 * 60 * 1000;
    /** Words a minute, read aloud: what the time on a book is counted with. */
    private static final int WORDS_A_MINUTE = 150;
    /** How many books the search shows. */
    static final int SHOWN = 20;
    /** Pages of the list, a hundred books each: far more than the library has. */
    private static final int MAX_PAGES = 60;
    private static final int MAX_CHARS = 4_000_000;
    private static final int FAILS = 3;
    private static final int FAILS_IN_A_ROW = 3;

    private Edited() {
    }

    // The list of all books.

    private static volatile List<EditedText.Book> books;
    private static long made;
    /**
     * When asking for the list last failed, and why, and when it may be asked for again: an hour
     * after the collection refused (it counts what each address asks), two minutes after a lost
     * connection.
     */
    private static long failedAt;
    private static IOException failure;
    private static long pause;
    private static final long BRIEF = 2L * 60 * 1000;
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** Pages already had from an asking that broke off: the next one, within the day, goes on from there. */
    private static final List<EditedText.Book> partial = new ArrayList<EditedText.Book>();
    private static int partialPages;
    private static long partialAt;
    /** Whether the list is being read ahead now, and who is waiting to hear that it is done. */
    private static boolean warming;
    private static final List<Runnable> waiting = new ArrayList<Runnable>();
    private static final long HOUR = 60L * 60 * 1000;
    /** Guards what is kept in memory; held only for moments, never over the network. */
    private static final Object STATE = new Object();
    /** One asking for the list at a time. */
    private static final Object ASKING = new Object();

    /** Whether the list is in memory now; never reads or waits. */
    static boolean ready() {
        return books != null;
    }

    /** How many books the list in memory has; 0 when it is not read yet. Never reads or waits. */
    static int count() {
        List<EditedText.Book> b = books;
        return b == null ? 0 : b.size();
    }

    /**
     * Reads the kept list and asks for a fresh one if it is old, on a thread of its own; one at a
     * time, and then tells. Nothing when it is already under way.
     */
    static void warm(final Context c, Runnable then) {
        synchronized (waiting) {
            if (then != null) {
                waiting.add(then);
            }
            if (warming) {
                return;
            }
            warming = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    all(c);
                } catch (IOException | RuntimeException e) {
                    Diag.log(c, "edited: the list not had ahead", e);
                }
                List<Runnable> told;
                synchronized (waiting) {
                    warming = false;
                    told = new ArrayList<Runnable>(waiting);
                    waiting.clear();
                }
                for (Runnable r : told) {
                    r.run();
                }
            }
        }, "edited-list").start();
    }

    private static boolean fresh() {
        long now = System.currentTimeMillis();
        return books != null && (now - made < WEEK && made <= now || now - failedAt < pause);
    }

    /**
     * The list of all books: the kept one, asked for again when it is a week old. When asking
     * fails, the old list serves and is not asked for again within the hour; without one, the
     * failure is thrown.
     */
    static List<EditedText.Book> all(Context c) throws IOException {
        synchronized (STATE) {
            load(c);
            if (fresh()) {
                return books;
            }
        }
        synchronized (ASKING) {
            synchronized (STATE) {
                // Another asking may have brought it while this one waited.
                if (fresh()) {
                    return books;
                }
                // Without a list, a failure within its pause is said again rather than asked again.
                if (books == null && failure != null && System.currentTimeMillis() - failedAt < pause) {
                    throw failure;
                }
            }
            try {
                List<EditedText.Book> got = fetchList(c);
                synchronized (STATE) {
                    books = got;
                    made = System.currentTimeMillis();
                    failedAt = 0;
                    save(c);
                }
                Diag.mark(c, "edited: the list asked for, " + got.size() + " books");
                return got;
            } catch (IOException e) {
                synchronized (STATE) {
                    failedAt = System.currentTimeMillis();
                    failure = e;
                    pause = e instanceof Refused ? HOUR : BRIEF;
                    if (books == null) {
                        throw e;
                    }
                    Diag.log(c, "edited: the list not renewed, the kept one serves", e);
                    return books;
                }
            }
        }
    }

    private static File listFile(Context c) {
        return new File(new File(c.getFilesDir(), "edited"), "list.tsv");
    }

    /** Reads the kept list into memory once; nothing when there is none or it cannot be read. */
    private static void load(Context c) {
        if (books != null) {
            return;
        }
        File f = listFile(c);
        if (!f.isFile()) {
            return;
        }
        List<EditedText.Book> got = new ArrayList<EditedText.Book>();
        long when = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("1\t")) {
                return;
            }
            when = Long.parseLong(line.substring(2).trim());
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) {
                    continue;
                }
                EditedText.Book b = new EditedText.Book();
                b.repo = p[0];
                b.page = p[1];
                b.title = p[2];
                b.author = p[3];
                b.translator = p[4];
                got.add(b);
            }
        } catch (NumberFormatException | IOException e) {
            return;
        }
        books = got;
        made = when;
    }

    private static void save(Context c) {
        File f = listFile(c);
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.write("1\t" + made + "\n");
            for (EditedText.Book b : books) {
                w.write(cell(b.repo) + "\t" + cell(b.page) + "\t" + cell(b.title) + "\t" + cell(b.author) + "\t"
                        + cell(b.translator) + "\n");
            }
        } catch (IOException e) {
            Diag.log(c, "edited: the list not kept", e);
            return;
        }
        if (!tmp.renameTo(f)) {
            Diag.mark(c, "edited: the list not kept");
        }
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Asks for every page of the list; a page had in an asking broken off within the hour is not asked twice. */
    private static List<EditedText.Book> fetchList(Context c) throws IOException {
        Map<String, String> t = talk(c);
        List<EditedText.Book> out = new ArrayList<EditedText.Book>();
        Set<String> seen = new HashSet<String>();
        int from = 1;
        if (partialPages > 0 && System.currentTimeMillis() - partialAt < DAY) {
            out.addAll(partial);
            for (EditedText.Book b : partial) {
                seen.add(b.repo);
            }
            from = partialPages + 1;
        }
        partial.clear();
        partialPages = 0;
        for (int page = from; page <= MAX_PAGES; page++) {
            String body;
            try {
                body = text(c, String.format(need(t, "list"), page), 4 * 1024 * 1024, true);
            } catch (IOException e) {
                // What came so far is kept for the next asking.
                partial.addAll(out);
                partialPages = page - 1;
                partialAt = System.currentTimeMillis();
                throw e;
            }
            JSONArray a;
            try {
                a = new JSONArray(body);
            } catch (JSONException e) {
                throw new IOException("edited: the list is not readable");
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optBoolean("archived") || o.optBoolean("fork")) {
                    continue;
                }
                EditedText.Book b = EditedText.fromLine(o.optString("name"), o.optString("homepage"),
                        o.optString("description"), need(t, "site"), need(t, "lead"), need(t, "translated"));
                if (b != null && seen.add(b.repo)) {
                    out.add(b);
                }
            }
            if (a.length() < 100) {
                break;
            }
        }
        if (out.isEmpty()) {
            throw new IOException("edited: the list is empty");
        }
        return out;
    }

    /** Books whose title or author holds every word asked for; titles that match come first. */
    static List<EditedText.Book> search(Context c, String query) throws IOException {
        List<EditedText.Book> all = all(c);
        String[] words = EditedText.plain(query).trim().split("[\\s,.;:]+");
        List<EditedText.Book> titled = new ArrayList<EditedText.Book>();
        List<EditedText.Book> rest = new ArrayList<EditedText.Book>();
        for (EditedText.Book b : all) {
            String title = EditedText.plain(b.title);
            String where = title + " " + EditedText.plain(b.author) + " " + EditedText.plain(b.translator);
            boolean every = true;
            boolean inTitle = true;
            for (String w : words) {
                if (w.isEmpty()) {
                    continue;
                }
                if (!where.contains(w)) {
                    every = false;
                    break;
                }
                inTitle &= title.contains(w);
            }
            if (every) {
                (inTitle ? titled : rest).add(b);
            }
        }
        titled.addAll(rest);
        List<EditedText.Book> out = titled.size() > SHOWN ? new ArrayList<EditedText.Book>(titled.subList(0, SHOWN))
                : titled;
        Diag.mark(c, "edited: search, " + out.size() + " shown");
        return out;
    }

    /** Every author with the number of their books, by the name they are sorted by. */
    static List<String[]> authors(Context c) throws IOException {
        Map<String, Integer> n = new LinkedHashMap<String, Integer>();
        for (EditedText.Book b : all(c)) {
            String a = b.author.isEmpty() ? "" : b.author;
            n.put(a, n.containsKey(a) ? n.get(a) + 1 : 1);
        }
        List<String[]> out = new ArrayList<String[]>();
        for (Map.Entry<String, Integer> e : n.entrySet()) {
            if (!e.getKey().isEmpty()) {
                out.add(new String[] {e.getKey(), String.valueOf(e.getValue())});
            }
        }
        Collections.sort(out, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int k = EditedText.sortName(a[0]).compareTo(EditedText.sortName(b[0]));
                return k != 0 ? k : a[0].compareTo(b[0]);
            }
        });
        return out;
    }

    /** One author's books, by title. */
    static List<EditedText.Book> by(Context c, String author) throws IOException {
        List<EditedText.Book> out = new ArrayList<EditedText.Book>();
        for (EditedText.Book b : all(c)) {
            if (b.author.equals(author)) {
                out.add(b);
            }
        }
        Collections.sort(out, new Comparator<EditedText.Book>() {
            @Override
            public int compare(EditedText.Book a, EditedText.Book b) {
                return EditedText.plain(bareTitle(a.title)).compareTo(EditedText.plain(bareTitle(b.title)));
            }
        });
        return out;
    }

    /** A title without the article it begins with, for sorting. */
    private static String bareTitle(String t) {
        return t.replaceFirst("^(?i)(the|a|an)\\s+", "");
    }

    // The latest books.

    /** The latest books, from the feed kept for half a day; the kept feed serves when asking fails. */
    static List<EditedText.Book> latest(Context c) throws IOException {
        File f = new File(cacheDir(c), "latest.xml");
        String xml = null;
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < HALF_DAY) {
            xml = read(f);
        }
        if (xml == null) {
            try {
                xml = text(c, need(talk(c), "latest"), 2 * 1024 * 1024, false);
                write(f, xml.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (!f.isFile()) {
                    throw e;
                }
                xml = read(f);
            }
        }
        List<EditedText.Book> out = EditedText.readFeed(xml, need(talk(c), "site"));
        Diag.mark(c, "edited: the latest, " + out.size());
        return out;
    }

    /** The latest books as kept, without asking; empty when none are kept. */
    static List<EditedText.Book> latestKept(Context c) {
        File f = new File(cacheDir(c), "latest.xml");
        try {
            return f.isFile() ? EditedText.readFeed(read(f), need(talk(c), "site")) : new ArrayList<EditedText.Book>();
        } catch (IOException | RuntimeException e) {
            return new ArrayList<EditedText.Book>();
        }
    }

    // One book.

    /** A book's description, kept after the first time it is asked for. */
    static EditedText.Package about(Context c, EditedText.Book b) throws IOException {
        File f = new File(new File(cacheDir(c), "books"), safe(b.repo) + ".opf");
        if (f.isFile()) {
            return EditedText.readPackage(read(f));
        }
        String opf = text(c, raw(c, b, need(talk(c), "package")), 1024 * 1024, false);
        write(f, opf.getBytes(StandardCharsets.UTF_8));
        return EditedText.readPackage(opf);
    }

    /** A book's description if it is kept; null when it was never asked for. */
    static EditedText.Package aboutKept(Context c, EditedText.Book b) {
        File f = new File(new File(cacheDir(c), "books"), safe(b.repo) + ".opf");
        try {
            return f.isFile() ? EditedText.readPackage(read(f)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A book's small cover as a picture about the given width; asked for once, then kept. Null if none. */
    static Bitmap thumb(Context c, EditedText.Book b, int width) {
        File f = new File(new File(cacheDir(c), "covers"), safe(b.repo) + ".jpg");
        if (!f.isFile()) {
            try {
                Map<String, String> t = talk(c);
                String address = !b.thumb.isEmpty() ? b.thumb : need(t, "site") + b.page + need(t, "thumb");
                write(f, whole(bytes(c, address, 1024 * 1024, false), 1024 * 1024));
            } catch (IOException | RuntimeException e) {
                Diag.markOnce(c, "edited-thumb", "edited: a small cover not had: " + e.getMessage());
                return null;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap got = BitmapFactory.decodeFile(f.getPath(), o);
        if (got == null) {
            f.delete();
        }
        return got;
    }

    /** About how long the book is aloud, counted from its words. */
    static String aloud(Context c, int words) {
        if (words <= 0) {
            return "";
        }
        int minutes = Math.max(10, Math.round(words / (float) WORDS_A_MINUTE / 10f) * 10);
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return c.getString(R.string.edited_aloud_minutes, m);
        }
        return m == 0 ? c.getString(R.string.edited_aloud_hours, h) : c.getString(R.string.edited_aloud, h, m);
    }

    /** The genres among the labels that the app has words for, in those words, joined. */
    static String genres(Context c, List<String> labels) {
        Map<String, String> words = new LinkedHashMap<String, String>();
        for (String line : c.getResources().getStringArray(R.array.edited_genres)) {
            String[] p = line.split("\\|", 2);
            if (p.length == 2) {
                words.put(p[0], p[1]);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String k : EditedText.genres(labels, words.keySet())) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(words.get(k));
        }
        return out.toString();
    }

    /** Puts the book together from its files and writes it, its own cover inside, as one book file. */
    static FreeBooks.Made assemble(Context c, EditedText.Book book, FreeBooks.Progress p) throws IOException {
        // Asked for afresh: a book is corrected now and then, and its files may have changed.
        Map<String, String> t = talk(c);
        String opf = text(c, raw(c, book, need(t, "package")), 1024 * 1024, false);
        write(new File(new File(cacheDir(c), "books"), safe(book.repo) + ".opf"), opf.getBytes(StandardCharsets.UTF_8));
        EditedText.Package pack = EditedText.readPackage(opf);
        if (pack.spine.isEmpty()) {
            throw new IOException("edited: no files to read");
        }
        int total = pack.spine.size() + 1;
        List<String> names = new ArrayList<String>();
        List<List<String>> parts = new ArrayList<List<String>>();
        int chars = 0;
        int done = 0;
        int failed = 0;
        int inARow = 0;
        boolean cut = false;
        String carried = "";
        p.step(0, total);
        for (String href : pack.spine) {
            if (p.stopped()) {
                throw new FreeBooks.Stopped();
            }
            String x = fetchTwice(c, raw(c, book, need(t, "text") + href), p);
            p.step(++done, total);
            if (x == null) {
                failed++;
                if (++inARow >= FAILS_IN_A_ROW || failed > Math.max(FAILS, pack.spine.size() / 4)) {
                    throw new IOException("edited: the library stopped answering");
                }
                continue;
            }
            inARow = 0;
            EditedText.Part part = EditedText.readPart(x);
            if (part.skipped) {
                continue;
            }
            if (chars >= MAX_CHARS) {
                // Past the limit only the pages about the edition may follow; real text means the book is cut.
                if (!part.texts.isEmpty()) {
                    cut = true;
                    break;
                }
                continue;
            }
            for (int i = 0; i < part.texts.size(); i++) {
                String name = part.names.get(i);
                if (!carried.isEmpty()) {
                    // A part's title from a file of its own goes before its first chapter.
                    name = EditedText.joinTitles(carried, name);
                    carried = "";
                }
                names.add(name);
                parts.add(part.texts.get(i));
                for (String s : part.texts.get(i)) {
                    chars += s.length();
                }
            }
            if (!part.carried.isEmpty()) {
                carried = EditedText.joinTitles(carried, part.carried);
            }
        }
        if (parts.isEmpty() || chars == 0) {
            throw new IOException("edited: no text");
        }
        byte[] cover = null;
        try {
            byte[] big = whole(bytes(c, raw(c, book, need(t, "cover")), 8 * 1024 * 1024, false), 8 * 1024 * 1024);
            cover = small(big);
        } catch (IOException | RuntimeException e) {
            Diag.log(c, "edited: the cover not had, the book goes without", e);
        }
        p.step(++done, total);
        String title = pack.title.isEmpty() ? book.title : pack.title;
        String author = pack.author().isEmpty() ? book.author : pack.author();
        File dir = new File(c.getCacheDir(), "library");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("edited: no room");
        }
        File out = new File(dir, fileName(title, author) + ".fb2");
        try (Writer o = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            LibraryPage.fb2(o, title, author, "en", names, parts, cover);
        }
        Diag.mark(c, "edited: book put together, " + parts.size() + " parts of " + pack.spine.size() + " files, "
                + chars / 1000 + " thousand letters" + (cover == null ? ", no cover" : ", its cover inside")
                + (failed > 0 ? ", " + failed + " files missing" : "") + (cut ? ", cut at the limit" : ""));
        FreeBooks.Made m = new FreeBooks.Made();
        m.file = out;
        m.cut = cut;
        m.missing = failed;
        return m;
    }

    /** The cover scaled down to what a book file carries; null if it cannot be read. */
    private static byte[] small(byte[] big) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(big, 0, big.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 600) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(big, 0, big.length, o);
        return b == null ? null : BookCover.small(b);
    }

    /** A file of the book, tried twice; null when it cannot be had. */
    private static String fetchTwice(Context c, String address, FreeBooks.Progress p) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return text(c, address, 8 * 1024 * 1024, false);
            } catch (IOException e) {
                if (p.stopped()) {
                    throw new FreeBooks.Stopped();
                }
                if (attempt == 0) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        throw new FreeBooks.Stopped();
                    }
                } else {
                    Diag.log(c, "edited: a file could not be had", e);
                }
            }
        }
        return null;
    }

    private static String fileName(String title, String author) {
        String t = (title + (author.isEmpty() ? "" : " (" + author + ")"))
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t.isEmpty() ? "book" : t;
    }

    // The network and the kept files.

    private static String raw(Context c, EditedText.Book b, String path) throws IOException {
        return String.format(need(talk(c), "raw"), b.repo, path);
    }

    /** A picture that filled its limit was cut short on the way: no picture. */
    private static byte[] whole(byte[] data, int limit) throws IOException {
        if (data.length >= limit) {
            throw new IOException("edited: a picture too big");
        }
        return data;
    }

    /** An address or fixed words from the bundle; a missing one is a failure, not a null. */
    private static String need(Map<String, String> t, String key) throws IOException {
        String v = t.get(key);
        if (v == null) {
            throw new IOException("edited: no " + key + " in the bundle");
        }
        return v;
    }

    private static File cacheDir(Context c) {
        return new File(c.getCacheDir(), "edited");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String text(Context c, String address, int max, boolean api) throws IOException {
        return new String(bytes(c, address, max, api), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Context c, String address, int max, boolean api) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        // Every program says who it is.
        h.setRequestProperty("User-Agent", lines(c, "talk/wiki-agent.txt").get(0));
        if (api) {
            h.setRequestProperty("Accept", "application/json");
        }
        try {
            int code = h.getResponseCode();
            if (code == 403 || code == 429) {
                throw new Refused(code);
            }
            if (code != 200) {
                throw new IOException("edited: http " + code);
            }
            try (InputStream in = h.getInputStream()) {
                return BookText.readAll(in, max);
            }
        } finally {
            h.disconnect();
        }
    }

    /** The collection said no: too many asks from this address for now. */
    private static final class Refused extends IOException {
        Refused(int code) {
            super("edited: refused, http " + code);
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(BookText.readAll(in, 8 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("edited: no room");
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("edited: not kept");
        }
    }

    private static Map<String, String> talk;

    /** The addresses and fixed words, read once; a failed reading is tried again next time. */
    private static synchronized Map<String, String> talk(Context c) {
        if (talk == null) {
            Map<String, String> t = new HashMap<String, String>();
            try {
                for (String line : lines(c, "talk/edited.txt")) {
                    String[] p = line.split("\\|", 2);
                    if (p.length == 2) {
                        t.put(p[0], p[1]);
                    }
                }
            } catch (IOException e) {
                Diag.log(c, "edited: no addresses", e);
                return t;
            }
            talk = t;
        }
        return talk;
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
