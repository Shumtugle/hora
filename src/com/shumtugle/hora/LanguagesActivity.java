package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

/**
 * The voice and the languages it reads in. The voice itself (the native
 * speech model) and each further language are fetched separately from the
 * app, by Wi-Fi unless asked otherwise, or taken from a file. The room only
 * says what is there and what can be done next.
 */
public final class LanguagesActivity extends Activity {
    private static final int PICK_VOICE = 71;
    private static final int PICK_ENGLISH = 72;

    private int built;
    private PackPanel voice;
    private PackPanel english;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout list = Kit.room(this, getString(R.string.lang_title));
        list.addView(Kit.lead(this, getString(R.string.lang_lead)));
        list.addView(Kit.section(this, getString(R.string.lang_russian)));
        voice = new PackPanel(this, LanguagePack.NATIVE, PICK_VOICE, 100, R.string.voice_pack_bad_file,
                R.string.voice_pack_note, false, null);
        list.addView(voice.view(), Kit.wide());
        list.addView(Kit.section(this, getString(R.string.lang_english)));
        english = new PackPanel(this, LanguagePack.ENGLISH, PICK_ENGLISH, 100, R.string.lang_bad_file,
                R.string.lang_not_yet, true, null);
        list.addView(english.view(), Kit.wide());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        voice.resume();
        english.resume();
    }

    @Override
    protected void onPause() {
        voice.pause();
        english.pause();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (!voice.result(request, result, data)) {
            english.result(request, result, data);
        }
    }
}
