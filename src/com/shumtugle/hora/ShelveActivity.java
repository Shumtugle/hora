package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;


/**
 * Invisible entry point for a book file: sent from another app, opened with
 * Hora, or chosen on the shelf. The permission to read the file ends with
 * this screen, so the file is copied into Hora's folders first and put on the
 * shelf from there; a book sent again under the same name replaces the copy
 * and keeps its place. A book format, or anything chosen inside the app, goes
 * to the books; another file sent from outside goes to the temporary books.
 * Either way the book screen opens on it; it plays only when chosen on the shelf, where a
 * touch means "read this", and otherwise waits for play.
 */
public final class ShelveActivity extends Activity {
    static final String EXTRA_PLAY = "play";
    /** Set when the book was chosen inside the app: whatever its format, it is a book. */
    static final String EXTRA_KEEP = "keep";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Uri from = source(getIntent());
        if (from == null) {
            finish();
            return;
        }
        final boolean play = getIntent().getBooleanExtra(EXTRA_PLAY, false);
        String sentName = null;
        try {
            sentName = BookActivity.displayName(this, from);
        } catch (RuntimeException ignored) {
            // The name decides only whether this is an archive; a book is handled below as before.
        }
        if (Transfer.isArchive(sentName)) {
            startActivity(new Intent(this, RestoreActivity.class).putExtra(RestoreActivity.EXTRA_URI, from.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            finish();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String name = nameOf(from);
                Uri kept = null;
                boolean temp = !getIntent().getBooleanExtra(EXTRA_KEEP, false) && !Books.bookFormat(name);
                String trouble = null;
                if (!BookText.readable(name)) {
                    trouble = getString(R.string.book_unreadable, name);
                } else {
                    try {
                        kept = Books.keep(ShelveActivity.this, from, name, temp);
                    } catch (IOException | SecurityException e) {
                        Diag.log(ShelveActivity.this, "shelf: the sent book could not be copied", e);
                        trouble = getString(R.string.shelve_failed, name);
                    }
                }
                final Uri book = kept;
                final boolean temporary = temp;
                final String said = trouble;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (book == null) {
                            Toast.makeText(ShelveActivity.this, said, Toast.LENGTH_LONG).show();
                        } else {
                            Diag.mark(ShelveActivity.this, temporary ? "shelf: a sent file kept for a while"
                                    : "shelf: a book kept");
                            BookActivity.open(ShelveActivity.this, book.toString(), name, play);
                            startActivity(new Intent(ShelveActivity.this, BookActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                        finish();
                    }
                });
            }
        }, "shelve").start();
    }

    /** The sent file's name; a plain text sent without an ending gets one, so the reader knows it. */
    private String nameOf(Uri from) {
        String name = null;
        try {
            name = BookActivity.displayName(this, from);
        } catch (RuntimeException e) {
            Diag.log(this, "shelf: the sent book has no readable name", e);
        }
        if (name == null || name.trim().isEmpty()) {
            name = "book";
        }
        String type = getIntent().getType();
        if (!BookText.readable(name) && type != null && type.startsWith("text/plain")) {
            name = name + ".txt";
        }
        return name;
    }

    /** The file in what was sent: a share carries it as a stream, an "open with" as data. */
    static Uri source(Intent in) {
        if (in == null) {
            return null;
        }
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            return u;
        }
        return in.getData();
    }

}
