package com.shumtugle.hora;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Hora's own folder in the phone's storage, for what the user brings and
 * takes away: models, samples, dictionaries, recordings, saved talks, audio
 * books. The user shows the folder once with the system's folder picker;
 * Hora then sees that folder and nothing else of the phone.
 */
final class HoraFolder {
    static final int PICK = 61;
    private static final String KEY = "hora_folder";
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;

    private HoraFolder() {
    }

    /** The folder the user showed, if Hora may still use it. */
    static Uri tree(Context c) {
        String s = c.getSharedPreferences("hora", Context.MODE_PRIVATE).getString(KEY, null);
        if (s == null) {
            return null;
        }
        Uri u = Uri.parse(s);
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) {
                return u;
            }
        }
        return null;
    }

    /** Opens the system's folder picker; the answer comes back to onActivityResult with PICK. */
    static void ask(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        // The picker opens where the folder is expected, at the top of the phone's storage, so one
        // touch on it is usually enough; where that place does not exist, the picker starts as usual.
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + a.getString(R.string.folder_root)));
        a.startActivityForResult(i, PICK);
    }

    /** Keeps the folder the user picked. Returns whether it can be used. */
    static boolean accept(Context c, Intent data) {
        if (data == null || data.getData() == null) {
            return false;
        }
        Uri u = data.getData();
        try {
            c.getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            return false;
        }
        c.getSharedPreferences("hora", Context.MODE_PRIVATE).edit().putString(KEY, u.toString()).apply();
        Diag.mark(c, "folder: chosen");
        return true;
    }

    /** A subfolder by name, made when it is missing. */
    static Uri sub(Context c, String name) throws IOException {
        Uri tree = tree(c);
        if (tree == null) {
            throw new IOException("no folder");
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri found = child(c, tree, DocumentsContract.getDocumentId(root), name);
        if (found != null) {
            return found;
        }
        try {
            Uri made = DocumentsContract.createDocument(c.getContentResolver(), root, DIR_MIME, name);
            if (made == null) {
                throw new IOException("folder not made");
            }
            return made;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static Uri child(Context c, Uri tree, String parentId, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        ContentResolver r = c.getContentResolver();
        try (Cursor cur = r.query(kids, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur == null) {
                return null;
            }
            while (cur.moveToNext()) {
                if (name.equals(cur.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** A subfolder by name, or null when it is not there; nothing is made. */
    static Uri findSub(Context c, String name) {
        Uri tree = tree(c);
        return tree == null ? null : child(c, tree, DocumentsContract.getTreeDocumentId(tree), name);
    }

    /** A file by name at the top of Hora's folder, or null. */
    static Uri findRoot(Context c, String name) {
        Uri tree = tree(c);
        return tree == null ? null : child(c, tree, DocumentsContract.getTreeDocumentId(tree), name);
    }

    /** A file by name at the top of Hora's folder, made when missing. */
    static Uri rootFile(Context c, String name) throws IOException {
        Uri tree = tree(c);
        if (tree == null) {
            throw new IOException("no folder");
        }
        Uri found = child(c, tree, DocumentsContract.getTreeDocumentId(tree), name);
        if (found != null) {
            return found;
        }
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try {
            Uri made = DocumentsContract.createDocument(c.getContentResolver(), root, "application/octet-stream", name);
            if (made == null) {
                throw new IOException("file not made");
            }
            return made;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** The files in a subfolder: address, name and last change of each. Empty if it cannot be read. */
    static java.util.List<String[]> list(Context c, Uri dir) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        Uri tree = tree(c);
        if (tree == null) {
            return out;
        }
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir));
        try (Cursor cur = c.getContentResolver().query(kids, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (DIR_MIME.equals(cur.getString(2))) {
                    continue;
                }
                out.add(new String[] {DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)).toString(),
                        cur.getString(1), String.valueOf(cur.getLong(3))});
            }
        } catch (RuntimeException e) {
            Diag.log(c, "folder: not listed", e);
        }
        return out;
    }

    /** A file by name in a subfolder, or null. */
    static Uri find(Context c, Uri dir, String name) {
        Uri tree = tree(c);
        return tree == null ? null : child(c, tree, DocumentsContract.getDocumentId(dir), name);
    }

    /** Writes any file into a subfolder and returns where it is. */
    static Uri writeBytes(Context c, String folder, String fileName, String mime, byte[] data) throws IOException {
        Uri dir = sub(c, folder);
        Uri file;
        try {
            file = DocumentsContract.createDocument(c.getContentResolver(), dir, mime, fileName);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
        if (file == null) {
            throw new IOException("file not made");
        }
        try (OutputStream out = c.getContentResolver().openOutputStream(file)) {
            if (out == null) {
                throw new IOException("file not open");
            }
            out.write(data);
        }
        Diag.mark(c, "folder: saved a file");
        return file;
    }

    /** Writes a text file into a subfolder and returns where it is. */
    static Uri writeText(Context c, String folder, String fileName, String text) throws IOException {
        Uri dir = sub(c, folder);
        Uri file;
        try {
            file = DocumentsContract.createDocument(c.getContentResolver(), dir, "text/plain", fileName);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
        if (file == null) {
            throw new IOException("file not made");
        }
        try (OutputStream out = c.getContentResolver().openOutputStream(file)) {
            if (out == null) {
                throw new IOException("file not open");
            }
            out.write(text.getBytes("UTF-8"));
        }
        Diag.mark(c, "folder: saved a file");
        return file;
    }
}
