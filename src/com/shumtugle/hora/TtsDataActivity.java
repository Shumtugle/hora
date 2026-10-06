package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;

/** Answers the system's "is voice data installed" and "sample text" queries. */
public final class TtsDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent();
        String action = getIntent().getAction();
        if (TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT.equals(action)) {
            result.putExtra("sampleText",
                    SpeechLanguage.resources(this).getString(R.string.tts_sample));
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        } else {
            ArrayList<String> available = new ArrayList<String>();
            available.add(SpeechLanguage.ISO3 + "-" + SpeechLanguage.COUNTRY3);
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                    new ArrayList<String>());
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        }
        finish();
    }
}
