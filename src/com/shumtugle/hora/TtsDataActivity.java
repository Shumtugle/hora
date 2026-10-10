package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;

/** Answers the system's "is voice data installed", "install voice data" and "sample text" requests. */
public final class TtsDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent();
        String action = getIntent().getAction();
        if (TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA.equals(action)) {
            // The system's "install voice data": the room where the voice and languages are fetched.
            startActivity(new Intent(this, LanguagesActivity.class));
            finish();
            return;
        }
        if (TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT.equals(action)) {
            result.putExtra("sampleText",
                    SpeechLanguage.resources(this).getString(R.string.tts_sample));
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        } else {
            ArrayList<String> available = new ArrayList<String>();
            ArrayList<String> missing = new ArrayList<String>();
            String own = SpeechLanguage.ISO3 + "-" + SpeechLanguage.COUNTRY3;
            boolean voice = Voice.ready(this);
            (voice ? available : missing).add(own);
            if (voice && LanguagePack.installed(this, LanguagePack.ENGLISH)) {
                available.add("eng-USA");
            }
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, missing);
            setResult(voice ? TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
                    : TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, result);
        }
        finish();
    }
}
