package com.shumtugle.hora;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * One place in the app's cache where the phone's camera puts a picture for
 * the talk. Nothing lands in the gallery: the picture lives only here and is
 * swept away when the next one is taken or the app starts again.
 */
public final class PhotoSlot extends ContentProvider {
    static final String AUTHORITY = "com.shumtugle.hora.photo";
    private static final String NAME = "shot.jpg";

    /** The address the camera writes to. */
    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + NAME);
    }

    static File file(Context c) {
        File dir = new File(c.getCacheDir(), "camera");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Diag.log(c, "camera: no room in the cache");
        }
        return new File(dir, NAME);
    }

    /** Empties the slot: the last picture is not kept. */
    static void sweep(Context c) {
        File f = file(c);
        if (f.exists() && !f.delete()) {
            Diag.log(c, "camera: old picture not removed");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = file(getContext());
        int m = mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
