package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Invisible entry point: shared or selected text is read aloud; a shared file goes on the shelf. */
public final class ReadActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction()) && in.getParcelableExtra(Intent.EXTRA_STREAM) != null) {
            // A file: Hora decides where it goes (books or temporary) and opens it, quiet until asked.
            startActivity(new Intent(in).setClass(this, ShelveActivity.class));
            finish();
            return;
        }
        CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (text == null) {
            text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        }
        if (text != null && text.toString().trim().length() > 0) {
            VoiceService.speak(this, text.toString());
        }
        finish();
    }
}
