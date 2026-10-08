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
        byte[] own = null;
        try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in != null) {
                own = fromBook(name, BookText.readAll(in, BookText.MAX_BYTES));
            }
        } catch (Exception e) {
            Diag.log(c, "export: no cover in the book", e);
        }
        if (own == null) {
            return null;
        }
        Bitmap b = BitmapFactory.decodeByteArray(own, 0, own.length);
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
            byte[] inner = firstEntry(data, ".fb2");
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
        java.util.Map<String, byte[]> files = new java.util.HashMap<String, byte[]>();
        String opfName = null;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String low = e.getName().toLowerCase(Locale.ROOT);
                if (low.endsWith(".opf") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")) {
                    files.put(e.getName(), BookText.readAll(z, BookText.MAX_BYTES));
                    if (low.endsWith(".opf")) {
                        opfName = e.getName();
                    }
                }
            }
        }
        if (opfName == null) {
            return null;
        }
        String opf = new String(files.get(opfName), "UTF-8");
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
        return files.get(path);
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] firstEntry(byte[] data, String ending) throws java.io.IOException {
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().toLowerCase(Locale.ROOT).endsWith(ending)) {
                    return BookText.readAll(z, BookText.MAX_BYTES);
                }
            }
        }
        return null;
    }

    private static byte[] small(Bitmap b) {
        float k = SIDE / (float) Math.max(b.getWidth(), b.getHeight());
        if (k < 1f) {
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
        return out.toByteArray();
    }
}
