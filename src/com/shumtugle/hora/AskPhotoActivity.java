package com.shumtugle.hora;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * "Ask about the photo" in another app's share sheet: a window with no face
 * that hands the picture, and the right to read it, to the talk and closes.
 */
public final class AskPhotoActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        Uri uri = in == null ? null : (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && in != null && in.getClipData() != null && in.getClipData().getItemCount() > 0) {
            uri = in.getClipData().getItemAt(0).getUri();
        }
        if (uri != null) {
            Intent talk = new Intent(this, TalkActivity.class)
                    .putExtra(TalkActivity.EXTRA_PHOTO, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // The right to read travels with the clip, not with the extra.
            talk.setClipData(ClipData.newRawUri("", uri));
            startActivity(talk);
        } else {
            Diag.log(this, "share: no picture in the request");
        }
        // A window with no face must close before it would be shown.
        finish();
    }
}
