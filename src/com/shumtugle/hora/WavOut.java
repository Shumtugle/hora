package com.shumtugle.hora;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

/** Writes 16-bit mono WAV files into the shared Downloads collection. */
final class WavOut {
    private WavOut() {
    }

    /** @return the display name the system actually gave the file */
    static String saveToDownloads(Context c, String name, float[] samples, int rate)
            throws IOException {
        return saveBytesToDownloads(c, name, encode(samples, rate));
    }

    static String saveBytesToDownloads(Context c, String name, byte[] wav) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("cannot create file");
        }
        OutputStream out = cr.openOutputStream(uri);
        if (out == null) {
            throw new IOException("cannot open file");
        }
        try {
            out.write(wav);
        } finally {
            out.close();
        }
        return name;
    }

    static byte[] encode(float[] s, int rate) {
        int data = s.length * 2;
        byte[] b = new byte[44 + data];
        put(b, 0, "RIFF");
        le32(b, 4, 36 + data);
        put(b, 8, "WAVEfmt ");
        le32(b, 16, 16);
        le16(b, 20, 1);
        le16(b, 22, 1);
        le32(b, 24, rate);
        le32(b, 28, rate * 2);
        le16(b, 32, 2);
        le16(b, 34, 16);
        put(b, 36, "data");
        le32(b, 40, data);
        for (int i = 0; i < s.length; i++) {
            int x = Math.round(Math.max(-1f, Math.min(1f, s[i])) * 32767f);
            b[44 + 2 * i] = (byte) x;
            b[45 + 2 * i] = (byte) (x >> 8);
        }
        return b;
    }

    private static void put(byte[] b, int o, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            b[o + i] = (byte) ascii.charAt(i);
        }
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int o, int v) {
        le16(b, o, v);
        le16(b, o + 2, v >> 16);
    }
}
