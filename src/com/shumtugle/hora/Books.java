package com.shumtugle.hora;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the books on the shelf live. Two folders inside Hora's own folder in
 * the phone's storage: one for books, one for what might be a book. A file
 * sent in a book's format, or added from inside the app, goes to the first;
 * a text or a page sent from elsewhere goes to the second, carries a mark on
 * the shelf, can be moved to the books with one touch, and goes away by itself
 * after a month unopened. The books folder is the shelf: a file put there by
 * hand appears on it, a file taken away leaves it.
 *
 * Until the user has shown Hora its folder, the same two folders are kept
 * inside the app, and move out as soon as the folder is known.
 */
final class Books {
    /** How long a temporary book stays without being opened. */
    private static final long TEMP_LIFE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String INNER_BOOKS = "books";
    private static final String INNER_TEMP = "temp";

    private Books() {
    }

    /** Formats that are books by their nature; anything else sent from outside is temporary. */
    static boolean bookFormat(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".epub") || n.endsWith(".fb2") || n.endsWith(".fb2.zip") || n.endsWith(".fb3");
    }

    private static String folder(Context c, boolean temp) {
        return c.getString(temp ? R.string.folder_temp : R.string.folder_books);
    }

    /** Copies a file into the books or the temporary books and returns the copy's address. */
    static Uri keep(Context c, Uri from, String name, boolean temp) throws IOException {
        String safe = new File(name).getName().replace('/', '_');
        if (HoraFolder.tree(c) != null) {
            Uri dir = HoraFolder.sub(c, folder(c, temp));
            Uri file = HoraFolder.find(c, dir, safe);
            try {
                if (file == null) {
                    file = DocumentsContract.createDocument(c.getContentResolver(), dir, "application/octet-stream", safe);
                }
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage());
            }
            if (file == null) {
                throw new IOException("file not made");
            }
            try (InputStream in = c.getContentResolver().openInputStream(from);
                 OutputStream out = c.getContentResolver().openOutputStream(file, "wt")) {
                copy(in, out);
            }
            return file;
        }
        File dir = new File(c.getFilesDir(), temp ? INNER_TEMP : INNER_BOOKS);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot make " + dir);
        }
        File part = new File(dir, safe + ".part");
        try (InputStream in = c.getContentResolver().openInputStream(from); OutputStream out = new FileOutputStream(part)) {
            copy(in, out);
        }
        File book = new File(dir, safe);
        if (book.exists() && !book.delete() || !part.renameTo(book)) {
            part.delete();
            throw new IOException("cannot put the copy in place");
        }
        return Uri.fromFile(book);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) {
            throw new IOException("cannot open");
        }
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
    }

    /** Whether a book on the shelf lives among the temporary ones. */
    static boolean isTemporary(Context c, String uri) {
        return inFolder(c, uri, true);
    }

    private static boolean inFolder(Context c, String uri, boolean temp) {
        if (uri.startsWith("file:")) {
            String path = Uri.parse(uri).getPath();
            return path != null && path.contains("/" + (temp ? INNER_TEMP : INNER_BOOKS) + "/");
        }
        try {
            String id = DocumentsContract.getDocumentId(Uri.parse(uri));
            return id.contains("/" + folder(c, temp) + "/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Moves a temporary book to the books; its place, chapters and reader go with it. */
    static String keepForGood(Context c, String uri) throws IOException {
        Library.Entry e = Library.get(c, uri);
        String name = e != null && !e.name.isEmpty() ? e.name : Uri.parse(uri).getLastPathSegment();
        Uri kept = keep(c, Uri.parse(uri), name, false);
        relocate(c, uri, kept.toString());
        delete(c, uri);
        Diag.mark(c, "shelf: a temporary book kept");
        return kept.toString();
    }

    private static void relocate(Context c, String from, String to) {
        Library.move(c, from, to);
        Reading.moved(c, from, to);
    }

    /** Whether the book's file is Hora's own copy, in the books or the temporary books. */
    static boolean owns(Context c, String uri) {
        return inFolder(c, uri, false) || inFolder(c, uri, true);
    }

    static void delete(Context c, String uri) {
        try {
            if (uri.startsWith("file:")) {
                String path = Uri.parse(uri).getPath();
                if (path != null) {
                    new File(path).delete();
                }
            } else {
                DocumentsContract.deleteDocument(c.getContentResolver(), Uri.parse(uri));
            }
        } catch (IOException | RuntimeException e) {
            Diag.log(c, "shelf: a file not removed", e);
        }
    }

    /**
     * Brings the shelf in line with the folders: temporary books unopened for a month go, books
     * taken out of the folder leave the shelf (their place stays remembered, should they come
     * back), and books put into it by hand appear. Slow: not on the main thread.
     */
    static void tidy(Context c) {
        long now = System.currentTimeMillis();
        String current = Reading.uri(c);
        Set<String> onShelf = new HashSet<String>();
        for (Library.Entry e : Library.all(c)) {
            onShelf.add(e.uri);
            if (e.uri.equals(current)) {
                continue;
            }
            if (isTemporary(c, e.uri) && now - lastTouched(c, e) > TEMP_LIFE_MS) {
                delete(c, e.uri);
                Library.remove(c, e.uri);
                Diag.mark(c, "shelf: a temporary book went after a month unopened");
            } else if ((inFolder(c, e.uri, false) || isTemporary(c, e.uri)) && !exists(c, e.uri)) {
                Library.remove(c, e.uri);
                Diag.mark(c, "shelf: a book taken out of the folder");
            }
        }
        if (HoraFolder.tree(c) == null) {
            return;
        }
        try {
            Uri dir = HoraFolder.sub(c, folder(c, false));
            android.content.res.Resources res = SpeechLanguage.resources(c);
            for (String[] f : HoraFolder.list(c, dir)) {
                if (onShelf.contains(f[0]) || !BookText.readable(f[1])) {
                    continue;
                }
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(f[0]))) {
                    Book b = BookText.read(f[1], in, res.getString(R.string.chapter_words),
                            res.getString(R.string.chapter_ordinals));
                    if (!b.paragraphs.isEmpty()) {
                        Library.found(c, f[0], f[1], b);
                        Diag.mark(c, "shelf: a book found in the folder");
                    }
                } catch (IOException | RuntimeException ex) {
                    Diag.log(c, "shelf: a file in the folder not read", ex);
                }
            }
        } catch (IOException e) {
            Diag.log(c, "shelf: the books folder not reached", e);
        }
    }

    /** When a book was last opened; one never opened counts from when its file last changed. */
    private static long lastTouched(Context c, Library.Entry e) {
        if (e.opened > 0) {
            return e.opened;
        }
        if (e.uri.startsWith("file:")) {
            String path = Uri.parse(e.uri).getPath();
            return path == null ? 0 : new File(path).lastModified();
        }
        return System.currentTimeMillis();
    }

    private static boolean exists(Context c, String uri) {
        if (uri.startsWith("file:")) {
            String path = Uri.parse(uri).getPath();
            return path != null && new File(path).isFile();
        }
        try (android.database.Cursor cur = c.getContentResolver().query(Uri.parse(uri),
                new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cur != null && cur.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Once the folder is known, the books kept inside the app move out into it. */
    static void moveOut(Context c) {
        if (HoraFolder.tree(c) == null) {
            return;
        }
        for (boolean temp : new boolean[] {false, true}) {
            File[] files = new File(c.getFilesDir(), temp ? INNER_TEMP : INNER_BOOKS).listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || f.getName().endsWith(".part")) {
                    continue;
                }
                try {
                    String from = Uri.fromFile(f).toString();
                    Uri to = keep(c, Uri.fromFile(f), f.getName(), temp);
                    relocate(c, from, to.toString());
                    f.delete();
                } catch (IOException | RuntimeException e) {
                    Diag.log(c, "shelf: a book not moved out", e);
                }
            }
        }
        Diag.mark(c, "shelf: books moved into the folder");
    }

    /** The books now on the shelf that live in Hora's folders, by file name. */
    static java.util.Map<String, String> byName(Context c) {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        for (Library.Entry e : Library.all(c)) {
            if (owns(c, e.uri) && e.name != null && !e.name.isEmpty()) {
                out.put(e.name, e.uri);
            }
        }
        return out;
    }

    /** Whether there are books inside the app waiting for the folder. */
    static boolean waitingInside(Context c) {
        for (String d : new String[] {INNER_BOOKS, INNER_TEMP}) {
            String[] names = new File(c.getFilesDir(), d).list();
            if (names != null && names.length > 0) {
                return true;
            }
        }
        return false;
    }

    static List<Library.Entry> shelf(Context c) {
        return Library.all(c);
    }
}
