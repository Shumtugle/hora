package com.shumtugle.hora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The picture for the front of an audiobook: the book's own cover when it
 * carries one (fb2 keeps it as a coded binary, epub as an image its package
 * names), or a picture the person chose by hand; never anything else. Always
 * a small JPEG, since every chapter file carries its own copy.
 */
final class BookCover {
    private static final int SIDE = 600;
    private static final int QUALITY = 85;

    private BookCover() {
    }

    /**
     * The book's own cover as a small JPEG, or null when the file carries none.
     * Nothing is ever made up or looked for beside the file: a picture lying in
     * the same folder may be anything, a private photo too.
     */
    static byte[] jpeg(Context c, String uri, String name) {
        if (Demo.is(c, uri)) {
            // The demo book's cover ships with the app.
            byte[] own = Demo.cover(c, uri);
            return own == null ? null : smallFrom(own);
        }
        try {
            // An audiobook is made once, so its cover may come from a longer book than the shelf reads.
            return own(c, uri, name, BookText.MAX_BYTES);
        } catch (Exception e) {
            Diag.log(c, "export: no cover in the book", e);
            return null;
        }
    }

    /** More than this is not read for a cover: such a book is taken as one without. */
    static final int READ_LIMIT = 24 * 1024 * 1024;

    /** Whether a book of this kind can carry a cover at all, by its file's name. */
    static boolean mayCarry(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".fb2") || n.endsWith(".fb2.zip") || n.endsWith(".epub");
    }

    /**
     * The book's own cover as a small JPEG; null when the book was read whole and carries none
     * (or is too long to be read for it). A book that could not be read throws, so a passing
     * failure is not taken for a book without a cover.
     */
    static byte[] own(Context c, String uri, String name) throws java.io.IOException {
        return own(c, uri, name, READ_LIMIT);
    }

    private static byte[] own(Context c, String uri, String name, int limit) throws java.io.IOException {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) {
            // An epub is read as it streams, twice, with nothing of it held whole: any size will do.
            byte[] picture = epubStreamed(c, Uri.parse(uri));
            return picture == null ? null : smallFrom(picture);
        }
        byte[] data;
        try (InputStream in = open(c, Uri.parse(uri))) {
            data = BookText.readAll(in, limit + 1);
        }
        if (data.length > limit) {
            return null;
        }
        byte[] picture;
        try {
            picture = n.endsWith(".fb2.zip") ? zippedFb2(data, limit) : fromBook(name, data);
        } catch (java.io.IOException | RuntimeException e) {
            // Read whole but not understood: a book without a cover, as far as can be told.
            return null;
        }
        return picture == null ? null : smallFrom(picture);
    }

    /** The book's stream; a book that cannot be opened is a failure to read, not a book without a cover. */
    private static InputStream open(Context c, Uri uri) throws java.io.IOException {
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new java.io.IOException("cover: the book cannot be opened");
            }
            return in;
        } catch (SecurityException e) {
            throw new java.io.IOException("cover: no access to the book", e);
        }
    }

    /** The fb2 inside a zip, its cover; no more of it unpacked than a book read for its cover may be. */
    private static byte[] zippedFb2(byte[] data, int limit) throws java.io.IOException {
        byte[] inner = firstEntry(data, ".fb2", limit + 1);
        return inner == null || inner.length > limit ? null : fb2(new String(inner, "UTF-8"));
    }

    /** An epub's cover, read as the book streams: its package on the first pass, the picture on the second. */
    private static byte[] epubStreamed(Context c, Uri uri) throws java.io.IOException {
        String opfName = null;
        byte[] opfData = null;
        try (ZipInputStream z = new ZipInputStream(open(c, uri))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                    opfName = e.getName();
                    opfData = BookText.readAll(z, 4 * 1024 * 1024);
                    break;
                }
            }
        } catch (java.util.zip.ZipException | IllegalArgumentException e) {
            return null;
        }
        String path = opfName == null ? null : coverPath(opfName, new String(opfData, "UTF-8"));
        if (path == null) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(open(c, uri))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(path)) {
                    return BookText.readAll(z, 16 * 1024 * 1024);
                }
            }
        } catch (java.util.zip.ZipException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** A picture decoded already scaled down near the size kept, then made small. */
    private static byte[] smallFrom(byte[] picture) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= SIDE) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(picture, 0, picture.length, o);
        return b == null ? null : small(b);
    }

    /** A picture the person chose for the cover themselves, turned the way it was held, as a small JPEG. */
    static byte[] chosen(Context c, Uri uri) {
        try {
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in);
            }
            if (b == null) {
                return null;
            }
            int turn = android.media.ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    turn = new android.media.ExifInterface(in).getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
                // No tags: the picture stands as it is.
            }
            int degrees = turn == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : turn == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : turn == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setRotate(degrees);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return small(b);
        } catch (Exception e) {
            Diag.log(c, "export: chosen cover unreadable", e);
            return null;
        }
    }

    /** The image a book file carries as its cover, as stored; null if none. */
    static byte[] fromBook(String name, byte[] data) throws java.io.IOException {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".fb2")) {
            return fb2(new String(data, "UTF-8"));
        }
        if (n.endsWith(".fb2.zip")) {
            byte[] inner = firstEntry(data, ".fb2", BookText.MAX_BYTES);
            return inner == null ? null : fb2(new String(inner, "UTF-8"));
        }
        if (n.endsWith(".epub")) {
            return epub(data);
        }
        return null;
    }

    private static final Pattern FB2_COVER = Pattern.compile(
            "(?s)<coverpage>.*?<image[^>]*?href=\"#([^\"]+)\"");

    private static byte[] fb2(String xml) {
        Matcher m = FB2_COVER.matcher(xml);
        if (!m.find()) {
            return null;
        }
        String id = Pattern.quote(m.group(1));
        Matcher bin = Pattern.compile("(?s)<binary[^>]*id=\"" + id + "\"[^>]*>(.*?)</binary>").matcher(xml);
        if (!bin.find()) {
            return null;
        }
        try {
            return android.util.Base64.decode(bin.group(1).replaceAll("\\s+", ""), android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final Pattern OPF_META = Pattern.compile("<meta[^>]*name=\"cover\"[^>]*content=\"([^\"]+)\"");
    private static final Pattern OPF_META_REVERSED = Pattern.compile("<meta[^>]*content=\"([^\"]+)\"[^>]*name=\"cover\"");
    private static final Pattern ITEM = Pattern.compile("<item\\s[^>]*>");

    private static byte[] epub(byte[] data) throws java.io.IOException {
        // Two passes over the book: the package first, then only the picture it names.
        String opfName = null;
        byte[] opfData = null;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(".opf")) {
                    opfName = e.getName();
                    opfData = BookText.readAll(z, BookText.MAX_BYTES);
                    break;
                }
            }
        }
        if (opfName == null) {
            return null;
        }
        String path = coverPath(opfName, new String(opfData, "UTF-8"));
        if (path == null) {
            return null;
        }
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(path)) {
                    return BookText.readAll(z, BookText.MAX_BYTES);
                }
            }
        }
        return null;
    }

    /** Where the package says the cover lies, as a path in the book; null when it names none. */
    private static String coverPath(String opfName, String opf) {
        String coverId = null;
        Matcher m = OPF_META.matcher(opf);
        if (m.find()) {
            coverId = m.group(1);
        } else {
            m = OPF_META_REVERSED.matcher(opf);
            if (m.find()) {
                coverId = m.group(1);
            }
        }
        String href = null;
        Matcher items = ITEM.matcher(opf);
        while (items.find()) {
            String item = items.group();
            String id = attr(item, "id");
            String props = attr(item, "properties");
            if ((coverId != null && coverId.equals(id)) || (props != null && props.contains("cover-image"))) {
                href = attr(item, "href");
                break;
            }
        }
        if (href == null) {
            return null;
        }
        int slash = opfName.lastIndexOf('/');
        String path = (slash < 0 ? "" : opfName.substring(0, slash + 1)) + Uri.decode(href);
        path = path.replaceAll("[^/]+/\\.\\./", "");
        return path;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] firstEntry(byte[] data, String ending, int limit) throws java.io.IOException {
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(ending)) {
                    return BookText.readAll(z, limit);
                }
            }
        }
        return null;
    }

    static byte[] small(Bitmap b) {
        float k = SIDE / (float) Math.max(b.getWidth(), b.getHeight());
        if (k < 1f) {
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
        return out.toByteArray();
    }
}
