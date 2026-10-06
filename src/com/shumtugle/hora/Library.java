package com.shumtugle.hora;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every book that has been opened: where it was left, its title and author,
 * its chapters, who read it, when it was last opened. Kept in one small file
 * that both processes read fresh, so the shelf and the reader agree.
 */
final class Library {
    private static final String FILE = "library.json";

    /** One book on the shelf. */
    static final class Entry {
        String uri = "";
        String name = "";
        String title = "";
        String author = "";
        int index;
        int total;
        int reader;
        long opened;
        final List<Book.Chapter> chapters = new ArrayList<Book.Chapter>();

        /** The chapter holding a paragraph, or -1. */
        int chapterAt(int paragraph) {
            int found = -1;
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).at <= paragraph) {
                    found = i;
                } else {
                    break;
                }
            }
            return found;
        }

        /** Where the chapter holding a paragraph ends (exclusive). */
        int chapterEnd(int chapter) {
            return chapter + 1 < chapters.size() ? chapters.get(chapter + 1).at : total;
        }

        int percent() {
            return total <= 0 ? 0 : Math.min(100, (index + 1) * 100 / total);
        }
    }

    private Library() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    /** All books, most recently opened first. */
    static synchronized List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        File f = file(c);
        if (!f.exists()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(Lexicon.readText(f));
            for (int i = 0; i < a.length(); i++) {
                out.add(fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Diag.log(c, "library: unreadable", e);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry x, Entry y) {
                return Long.compare(y.opened, x.opened);
            }
        });
        return out;
    }

    static Entry get(Context c, String uri) {
        for (Entry e : all(c)) {
            if (e.uri.equals(uri)) {
                return e;
            }
        }
        return null;
    }

    /** Records a book just opened, with what its file says about itself. */
    static synchronized void opened(Context c, String uri, String name, Book b, int at, int reader) {
        List<Entry> list = all(c);
        Entry e = null;
        for (Entry x : list) {
            if (x.uri.equals(uri)) {
                e = x;
            }
        }
        if (e == null) {
            e = new Entry();
            e.uri = uri;
            list.add(e);
        }
        e.name = name == null ? "" : name;
        e.title = b.title;
        e.author = b.author;
        e.total = b.paragraphs.size();
        e.index = at;
        e.reader = reader;
        e.opened = System.currentTimeMillis();
        e.chapters.clear();
        e.chapters.addAll(b.chapters);
        save(c, list);
    }

    /**
     * Records a book found in Hora's folder but never opened: it stands on the shelf with what
     * its file says about itself, after the books that have been read.
     */
    static synchronized void found(Context c, String uri, String name, Book b) {
        List<Entry> list = all(c);
        for (Entry x : list) {
            if (x.uri.equals(uri)) {
                return;
            }
        }
        Entry e = new Entry();
        e.uri = uri;
        e.name = name == null ? "" : name;
        e.title = b.title;
        e.author = b.author;
        e.total = b.paragraphs.size();
        e.chapters.addAll(b.chapters);
        list.add(e);
        save(c, list);
    }

    /** A book brought back from another phone: its place, reader and when it was last opened. */
    static synchronized void restored(Context c, String uri, int index, int reader, long opened) {
        List<Entry> list = all(c);
        for (Entry x : list) {
            if (x.uri.equals(uri)) {
                x.index = index;
                x.reader = reader;
                x.opened = opened;
            }
        }
        save(c, list);
    }

    /** A book that moved keeps everything under its new address. */
    static synchronized void move(Context c, String from, String to) {
        List<Entry> list = all(c);
        for (Entry x : list) {
            if (x.uri.equals(from)) {
                x.uri = to;
            }
        }
        save(c, list);
    }

    /** Moves a book's place; cheap enough to call at every paragraph. */
    static synchronized void progress(Context c, String uri, int index, int reader) {
        List<Entry> list = all(c);
        for (Entry e : list) {
            if (e.uri.equals(uri)) {
                if (e.index == index && e.reader == reader) {
                    return;
                }
                e.index = index;
                e.reader = reader;
                e.opened = System.currentTimeMillis();
                save(c, list);
                return;
            }
        }
    }

    static synchronized void remove(Context c, String uri) {
        List<Entry> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uri.equals(uri)) {
                list.remove(i);
            }
        }
        save(c, list);
    }

    private static void save(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                a.put(toJson(e));
            }
            File f = file(c);
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(a.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                Diag.mark(c, "library: not saved");
            }
            // The shelf travels in the transfer archive.
            Transfer.soon(c);
        } catch (JSONException | IOException e) {
            Diag.log(c, "library: not saved", e);
        }
    }

    private static JSONObject toJson(Entry e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uri", e.uri).put("name", e.name).put("title", e.title).put("author", e.author)
                .put("index", e.index).put("total", e.total).put("reader", e.reader).put("opened", e.opened);
        JSONArray ch = new JSONArray();
        for (Book.Chapter c : e.chapters) {
            ch.put(new JSONObject().put("at", c.at).put("label", c.label).put("first", c.first));
        }
        o.put("chapters", ch);
        return o;
    }

    private static Entry fromJson(JSONObject o) throws JSONException {
        Entry e = new Entry();
        e.uri = o.optString("uri");
        e.name = o.optString("name");
        e.title = o.optString("title");
        e.author = o.optString("author");
        e.index = o.optInt("index");
        e.total = o.optInt("total");
        e.reader = o.optInt("reader");
        e.opened = o.optLong("opened");
        JSONArray ch = o.optJSONArray("chapters");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) {
                JSONObject c = ch.getJSONObject(i);
                e.chapters.add(new Book.Chapter(c.optInt("at"), c.optString("label"), c.optString("first")));
            }
        }
        return e;
    }
}
