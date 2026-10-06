package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * Plain-text dictionary editor. Opened from settings, or from the text
 * selection menu, in which case the selected word is appended as a new rule.
 */
public final class LexiconActivity extends Activity {
    /** The look this screen was built with. */
    private int built;
    private static final int PICK_DICTIONARY = 2;

    private EditText editor;
    private TextView imported;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));
        root.addView(Ui.header(this, getString(R.string.lexicon), false),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        // What is already built in, so an empty list of one's own never reads as "no dictionaries".
        LinearLayout builtin = Ui.column(this);
        builtin.setBackground(Ui.round(Palette.SURFACE, 22, this));
        builtin.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView bt = Ui.text(this, getString(R.string.lexicon_builtin_title), 15, Palette.INK);
        bt.setTypeface(Palette.bodyStrong(this));
        builtin.addView(bt);
        TextView bb = Ui.text(this, getString(R.string.lexicon_builtin), 13, Palette.MUTED);
        bb.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams bbp = Ui.lp(Ui.MATCH, Ui.WRAP);
        bbp.topMargin = dp(6);
        builtin.addView(bb, bbp);
        LinearLayout.LayoutParams blp = Ui.lp(Ui.MATCH, Ui.WRAP);
        blp.topMargin = dp(12);
        root.addView(builtin, blp);

        imported = Ui.text(this, "", 13, Palette.MUTED);
        LinearLayout.LayoutParams imp = Ui.lp(Ui.MATCH, Ui.WRAP);
        imp.topMargin = dp(14);
        root.addView(imported, imp);

        LinearLayout tools = Ui.row(this);
        TextView importBtn = toolButton(R.string.lexicon_import);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                startActivityForResult(pick, PICK_DICTIONARY);
            }
        });
        TextView clearBtn = toolButton(R.string.lexicon_clear);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (java.io.File f : Lexicon.importedFiles(LexiconActivity.this)) {
                    f.delete();
                }
                showImported();
            }
        });
        tools.addView(importBtn);
        tools.addView(clearBtn);
        root.addView(tools);
        showImported();

        TextView own = Ui.text(this, getString(R.string.lexicon_own), 15, Palette.INK);
        own.setTypeface(Palette.bodyStrong(this));
        LinearLayout.LayoutParams op = Ui.lp(Ui.MATCH, Ui.WRAP);
        op.topMargin = dp(20);
        root.addView(own, op);
        TextView hint = Ui.text(this, getString(R.string.lexicon_hint), 13, Palette.MUTED);
        hint.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams hp = Ui.lp(Ui.MATCH, Ui.WRAP);
        hp.topMargin = dp(4);
        hp.bottomMargin = dp(10);
        root.addView(hint, hp);

        editor = new EditText(this);
        Ui.field(editor);
        editor.setTextColor(Palette.INK);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setBackground(Ui.round(Palette.SURFACE, 18, this));
        editor.setHint(R.string.lexicon_example);
        editor.setHintTextColor(Palette.HINT);
        editor.setPadding(dp(14), dp(12), dp(14), dp(12));
        editor.setHorizontallyScrolling(false);
        editor.setText(Lexicon.readText(this));
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView save = Ui.pill(this, getString(R.string.save), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        LinearLayout.LayoutParams bp = Ui.lp(Ui.MATCH, dp(48));
        bp.topMargin = dp(12);
        root.addView(save, bp);

        setContentView(root);
        appendSelection(getIntent());
    }

    private void appendSelection(Intent in) {
        CharSequence word = in.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (word == null || word.toString().trim().isEmpty()) {
            return;
        }
        String w = word.toString().trim();
        String current = editor.getText().toString();
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        String line = w + " = " + w;
        editor.append(prefix + line);
        editor.requestFocus();
        editor.setSelection(editor.length() - w.length(), editor.length());
    }

    private TextView toolButton(int label) {
        TextView b = Ui.pill(this, getString(label), Palette.SURFACE, Palette.LINE, Palette.INK, false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        p.topMargin = dp(8);
        p.rightMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private void showImported() {
        java.io.File[] fs = Lexicon.importedFiles(this);
        if (fs.length == 0) {
            imported.setText(R.string.lexicon_none);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (java.io.File f : fs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.getName().replaceAll("\\.txt$", ""));
        }
        imported.setText(getString(R.string.lexicon_imported, sb.toString()));
    }

    @Override
    protected void onActivityResult(int request, int result, final Intent data) {
        if (request != PICK_DICTIONARY || result != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    LexxImport.Result r = new LexxImport(SpeechLanguage.resources(LexiconActivity.this))
                            .importUri(LexiconActivity.this, uri, displayName(uri));
                    msg = getString(R.string.lexicon_import_done, r.name, r.rules);
                } catch (Throwable t) {
                    msg = getString(R.string.lexicon_import_failed, String.valueOf(t.getMessage()));
                }
                final String shown = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showImported();
                        Toast.makeText(LexiconActivity.this, shown, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private String displayName(android.net.Uri uri) {
        android.database.Cursor c = getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c == null) {
            return null;
        }
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    private void save() {
        try {
            Lexicon.writeText(this, editor.getText().toString());
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }
}
