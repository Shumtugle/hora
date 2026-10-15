package com.shumtugle.hora;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The talk, as a room: the voice on duty at the top, what was said below it
 * (hers on the left, yours on the right, the newest at the bottom), ready
 * requests as a strip, and the line to write in. What she did is shown under
 * her answer, so an answer is never mistaken for a promise.
 */
public final class TalkActivity extends Activity {
    /** One line of the talk: who said it, the words, and what was done. */
    private static final class Line {
        final boolean mine;
        final String text;
        final String trace;
        /** A file this line hands over, shown as an attachment with a way to share it; null if none. */
        String file;
        /** A picture that went with this line, as a file in the app's cache; null if none. */
        String image;
        /** The full camera shot behind the picture, kept so one touch can put it in the gallery; null if none. */
        String shot;
        /** Set once the shot has gone to the gallery. */
        boolean saved;
        /** Set when the line says the AI model is missing: a button under it fetches what is missing. */
        boolean needsModel;
        /** Where the answer came from, opened by a tap on the line under it; null if nowhere. */
        String link;

        Line(boolean mine, String text, String trace) {
            this.mine = mine;
            this.text = text;
            this.trace = trace == null ? "" : trace;
        }
    }

    /** The talk lasts as long as the app does; nothing of it is written anywhere. */
    private static final List<Line> talk = new ArrayList<Line>();
    private static final int KEPT = 60;
    /** Whether this talk has already offered the AI model; it is offered once, not on every miss. */
    private static boolean offeredModel;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int built;
    private int voice;
    private ImageView face;
    private TextView name;
    private TextView epithet;
    private ImageView backdrop;
    private LinearLayout lines;
    private ScrollView scroll;
    private EditText draft;
    private static final int PICK_IMAGE = 81;
    private static final int ASK_MIC = 82;
    /** The clip, and the room's loudness that takes its place while the microphone listens. */
    private ImageView clip;
    private Level level;
    /** The round button: listens when the field is empty, sends when it is not, stops a listening. */
    private ImageView go;
    /** The listening in progress; null when the microphone is closed. */
    private Listen listening;
    /** Whether the phone had been asked for the microphone before the request now on screen. */
    private boolean micAskedBefore;
    private LinearLayout attached;
    private ImageView attachedThumb;
    /** A picture picked for the next request, ready as a small JPEG; null if none. */
    private byte[] pending;
    /** The full camera shot behind the pending picture; null when the picture came from elsewhere. */
    private String pendingShot;
    /** The camera button: shown only when the model can look at pictures. */
    /** Whether the model can look at pictures, so the camera is offered. */
    private boolean eyes;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        // The same voice as on the main screen: the one chosen there answers here.
        voice = Prefs.role(this, Cast.TALK);
        setContentView(build());
        takeShared(getIntent());
        if (talk.isEmpty()) {
            // The voice opens with what it can do here, in its own words.
            add(new Line(false, Cast.say(this, R.array.voice_talk_open, voice), ""));
        } else {
            for (Line l : talk) {
                lines.addView(bubble(l));
            }
            toBottom();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showVoice(Prefs.role(this, Cast.TALK));
        showCamera();
        collectShot();
    }

    private int dp(float v) {
        return Kit.dp(this, v);
    }

    private View build() {
        FrameLayout whole = new FrameLayout(this);
        whole.setBackgroundColor(Palette.BG);
        backdrop = new ImageView(this);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        whole.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor((Palette.BG & 0x00FFFFFF) | 0xD1000000);
        whole.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = Ui.column(this);
        whole.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, Palette.SURFACE, 48, getString(R.string.ws_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back, Ui.lp(dp(48), dp(48)));
        face = Ui.portrait(this, voice);
        face.setPadding(dp(3), dp(3), dp(3), dp(3));
        face.setClickable(true);
        face.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TalkActivity.this, BrainActivity.class));
            }
        });
        LinearLayout.LayoutParams fp = Ui.lp(dp(52), dp(52));
        fp.leftMargin = dp(12);
        head.addView(face, fp);
        LinearLayout who = Ui.column(this);
        name = Ui.title(this, "", 26);
        who.addView(name);
        epithet = Ui.text(this, "", 13, Palette.MUTED);
        epithet.setVisibility(View.GONE);
        who.addView(epithet);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(12);
        head.addView(who, wp);
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(40));
        lines = Ui.column(this);
        lines.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(lines);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        sp.topMargin = dp(10);
        root.addView(scroll, sp);

        // Ready requests: a touch sends one as if it was written. They wrap onto a
        // second line rather than run past the edge, so every one of them is in sight.
        Kit.Flow chips = new Kit.Flow(this, 8);
        int[] ready = {R.string.home_time, R.string.home_weather, R.string.book_play, R.string.talk_where,
                R.string.bot_help_chip};
        // A button means one thing whatever the screen's language: its label shows in the talk,
        // and the same request in the language the voice understands goes to the bot.
        android.content.res.Resources voiceWords = SpeechLanguage.resources(this);
        for (int k = 0; k < ready.length; k++) {
            final String said = getString(ready[k]);
            final String meant = voiceWords.getString(ready[k]);
            TextView chip = Ui.pill(this, said, Palette.SURFACE, Palette.LINE, Palette.INK, false);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setMinHeight(dp(40));
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    send(said, meant);
                }
            });
            chips.addView(chip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        LinearLayout.LayoutParams stp = Ui.lp(Ui.MATCH, Ui.WRAP);
        stp.topMargin = dp(10);
        root.addView(chips, stp);

        LinearLayout line = Ui.row(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(Ui.round(Palette.SURFACE, 28, this));

        // One way to attach: the clip opens a sheet with the camera (when the model can see),
        // the phone's photo picker and the files.
        clip = Ui.iconButton(this, R.drawable.ic_attach, Palette.SURFACE, 40, getString(R.string.talk_attach));
        clip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attachSheet();
            }
        });
        line.setPadding(dp(6), dp(6), dp(6), dp(6));
        line.addView(clip, Ui.lp(dp(40), dp(40)));
        level = new Level(this);
        level.setVisibility(View.GONE);
        line.addView(level, Ui.lp(dp(40), dp(40)));
        draft = new EditText(this);
        Ui.field(draft);
        // No hint text: everybody knows what a message field is for; the screen reader still says it.
        draft.setContentDescription(getString(R.string.talk_hint));
        draft.setTextColor(Palette.INK);
        draft.setTextSize(16);
        draft.setTypeface(Palette.body(this));
        draft.setBackground(null);
        draft.setPadding(0, 0, dp(8), 0);
        draft.setSingleLine(true);
        draft.setImeOptions(EditorInfo.IME_ACTION_SEND);
        if (Build.VERSION.SDK_INT >= 31) {
            // A picture pasted into the field is attached, as if picked.
            draft.setOnReceiveContentListener(new String[] {"image/*"}, new android.view.OnReceiveContentListener() {
                @Override
                public android.view.ContentInfo onReceiveContent(View view, android.view.ContentInfo payload) {
                    android.content.ClipData clip = payload.getClip();
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        android.net.Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) {
                            attachImage(uri);
                            return null;
                        }
                    }
                    return payload;
                }
            });
        }
        draft.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence t, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                showGo();
            }
        });
        draft.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                sendDraft();
                return true;
            }
        });
        line.addView(draft, new LinearLayout.LayoutParams(0, dp(44), 1f));
        go = Ui.iconButton(this, R.drawable.ic_mic, Palette.ACCENT, 44, getString(R.string.talk_mic));
        go.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.ON_ACCENT));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goTouched();
            }
        });
        line.addView(go, Ui.lp(dp(44), dp(44)));
        // A picture waiting to go with the next words: a small preview with a way to drop it.
        attached = Ui.row(this);
        attached.setGravity(Gravity.CENTER_VERTICAL);
        attached.setVisibility(View.GONE);
        attachedThumb = new ImageView(this);
        attachedThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachedThumb.setClipToOutline(true);
        attachedThumb.setBackground(Ui.round(Palette.RAISED, 12, this));
        attached.addView(attachedThumb, Ui.lp(dp(56), dp(56)));
        TextView drop = Kit.link(this, getString(R.string.talk_attach_remove), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pending = null;
                pendingShot = null;
                attached.setVisibility(View.GONE);
                showGo();
            }
        });
        LinearLayout.LayoutParams dpp = Ui.lp(Ui.WRAP, Ui.WRAP);
        dpp.leftMargin = dp(12);
        attached.addView(drop, dpp);
        LinearLayout.LayoutParams ap = Ui.lp(Ui.MATCH, Ui.WRAP);
        ap.topMargin = dp(10);
        root.addView(attached, ap);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(10);
        root.addView(line, lp);
        Kit.edgeToEdge(this, root, dp(20), dp(16), dp(20), dp(12));
        showGo();
        return whole;
    }

    /**
     * A camera with nothing to look through its pictures is a promise the app
     * cannot keep, so the button is there only with the model and its photo part.
     * The pictures from the gallery and the files stay: they are the way to learn that a download is missing.
     */
    private void showCamera() {
        Brain b = Brain.get(this);
        eyes = b.hasModel() && b.has(Brain.SLOT_VISION) && !b.visionRefused();
    }

    /** The sheet behind the clip: camera, photos, files, one row each. */
    private void attachSheet() {
        final android.app.Dialog[] sheet = new android.app.Dialog[1];
        LinearLayout rows = Ui.column(this);
        if (eyes) {
            rows.addView(Kit.rowNav(this, getString(R.string.talk_camera), null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sheet[0].dismiss();
                    takePhoto();
                }
            }));
        }
        rows.addView(Kit.rowNav(this, getString(R.string.talk_photos), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet[0].dismiss();
                pickPhoto();
            }
        }));
        rows.addView(Kit.rowNav(this, getString(R.string.talk_files), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet[0].dismiss();
                pickFile();
            }
        }));
        sheet[0] = Kit.sheet(this, getString(R.string.talk_attach), rows, null, null);
    }

    private void showVoice(int v) {
        voice = v;
        Ui.setFace(face, v);
        face.setBackground(Ui.oval(Palette.BG, Palette.voice(v), 2, this));
        name.setText(Cast.name(this, v));
        Kit.backdrop(this, backdrop, v);
    }

    /** The round button: what it does depends on what is in the line. */
    private void goTouched() {
        if (listening != null) {
            listening.stop();
        } else if (draft.getText().toString().trim().isEmpty() && pending == null) {
            listen();
        } else {
            sendDraft();
        }
    }

    /** Shows the round button as what a touch would do now: listen, send or stop. */
    private void showGo() {
        if (go == null) {
            return;
        }
        boolean mic = draft.getText().toString().trim().isEmpty() && pending == null;
        int icon = listening != null ? R.drawable.ic_stop : mic ? R.drawable.ic_mic : R.drawable.ic_play;
        int label = listening != null ? R.string.talk_mic_stop : mic ? R.string.talk_mic : R.string.talk_send;
        go.setImageResource(icon);
        go.setContentDescription(getString(label));
    }

    /** Opens the microphone; the words appear in the field as they are heard. */
    private void listen() {
        if (!Listen.allowed(this)) {
            micAskedBefore = Prefs.micAsked(this);
            Prefs.setMicAsked(this);
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            return;
        }
        // No voice of Hora may be heard as a request: whatever one is saying stops first. Asked only
        // when one is speaking, so a quiet phone is not made to wake the speaking side for nothing.
        if (Hush.speaking(this)) {
            Hush.all(this);
        }
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        im.hideSoftInputFromWindow(draft.getWindowToken(), 0);
        draft.clearFocus();
        // The field only shows the words while the microphone is open: no cursor, no keyboard.
        draft.setEnabled(false);
        draft.setText("");
        draft.setHint(R.string.talk_listening);
        draft.setHintTextColor(Palette.HINT);
        clip.setVisibility(View.GONE);
        level.reset();
        level.setVisibility(View.VISIBLE);
        listening = Listen.start(this, new Listen.Heard() {
            @Override
            public void words(String sofar) {
                draft.setText(sofar);
            }

            @Override
            public void level(float loud) {
                level.push(loud);
            }

            @Override
            public void done(String line, boolean failed) {
                heard(line, failed);
            }
        });
        showGo();
    }

    /** The microphone has closed: the line goes out, or waits in the field when that was asked for. */
    private void heard(String line, boolean failed) {
        closeMic();
        if (line.isEmpty()) {
            draft.setText("");
            if (failed) {
                // Silence says nothing; a microphone that let her down is told in her line.
                add(new Line(false, getString(R.string.talk_mic_failed), ""));
            }
        } else if (Prefs.heardToField(this)) {
            draft.setText(line);
            draft.setSelection(draft.length());
        } else {
            // A picture that arrived while listening goes with the words, as if both were sent at once.
            draft.setText(line);
            sendDraft();
        }
        showGo();
    }

    /** Puts the line back as it is without the microphone. */
    private void closeMic() {
        listening = null;
        draft.setEnabled(true);
        draft.setHint(null);
        level.setVisibility(View.GONE);
        clip.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] asked, int[] given) {
        super.onRequestPermissionsResult(code, asked, given);
        if (code != ASK_MIC) {
            return;
        }
        if (given.length > 0 && given[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            listen();
        } else if (micAskedBefore && !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Refused for good: the system will not ask again, so the way to allow it is shown here.
            // A first prompt merely closed without an answer reads the same, hence the earlier ask.
            micSheet();
        }
    }

    private void micSheet() {
        TextView t = Ui.text(this, getString(R.string.talk_mic_needed), 15, Palette.MUTED);
        t.setLineSpacing(0, 1.3f);
        Kit.sheet(this, getString(R.string.talk_mic_title), t, getString(R.string.talk_mic_settings), new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", getPackageName(), null)));
                } catch (android.content.ActivityNotFoundException e) {
                    Diag.log(TalkActivity.this, "listen: no app settings screen");
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen closes the microphone and drops what it was hearing.
        if (listening != null) {
            listening.cancel();
            closeMic();
            draft.setText("");
            showGo();
        }
    }

    /** The room's loudness as a few bars, the newest on the right. */
    private static final class Level extends View {
        private final float[] last = new float[6];
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bar = new android.graphics.RectF();

        Level(android.content.Context c) {
            super(c);
            paint.setColor(Palette.ACCENT_TEXT);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void reset() {
            java.util.Arrays.fill(last, 0f);
            invalidate();
        }

        void push(float v) {
            System.arraycopy(last, 1, last, 0, last.length - 1);
            last[last.length - 1] = v;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = 3 * d;
            float gap = 2.5f * d;
            float total = last.length * w + (last.length - 1) * gap;
            float x = (getWidth() - total) / 2;
            float mid = getHeight() / 2f;
            float low = 4 * d;
            float high = getHeight() * 0.7f;
            for (float v : last) {
                float h = low + (high - low) * v;
                bar.set(x, mid - h / 2, x + w, mid + h / 2);
                c.drawRoundRect(bar, w / 2, w / 2, paint);
                x += w + gap;
            }
        }
    }

    private void sendDraft() {
        String text = draft.getText().toString().trim();
        if (text.isEmpty() && pending == null) {
            return;
        }
        if (pending != null) {
            byte[] jpeg = pending;
            pending = null;
            String shotFile = pendingShot;
            pendingShot = null;
            attached.setVisibility(View.GONE);
            draft.setText("");
            showGo();
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(draft.getWindowToken(), 0);
            look(text.isEmpty() ? getString(R.string.talk_image_default) : text, jpeg, shotFile);
            return;
        }
        draft.setText("");
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        im.hideSoftInputFromWindow(draft.getWindowToken(), 0);
        send(text);
    }

    /** A request: your line, then hers, spoken in her voice, with what was done under it. */
    private void send(String text) {
        send(text, text);
    }

    /** A request shown as one line and understood as another: a button's label and its meaning. */
    private void send(String shown, String text) {
        add(new Line(true, shown, ""));
        Bot.Answer a = Bot.handle(this, text, voice);
        switch (a.kind) {
            case Bot.TIME: {
                answer(TimePhrase.dateAndTime(SpeechLanguage.resources(this)), "");
                if (Bot.alsoAsks(this, text, "weather")) {
                    weather();
                }
                break;
            }
            case Bot.WEATHER:
                // Two questions in one ("the weather, and what time is it?"): both are answered.
                if (Bot.alsoAsks(this, text, "time")) {
                    answer(TimePhrase.dateAndTime(SpeechLanguage.resources(this)), "");
                }
                weather();
                break;
            case Bot.VOICE:
                Prefs.setRole(this, Cast.TALK, a.voice);
                Prefs.setHomeVoice(this, a.voice);
                Widgets.refresh(this);
                showVoice(a.voice);
                answer(a.text, a.trace);
                break;
            case Bot.SAVE:
                saveTalk();
                break;
            case Bot.FACT:
                lookUp(text, a);
                break;
            case Bot.SPEAK_TEXT:
            case Bot.UNKNOWN:
                // Not a command: free talk goes to the small model when there is one.
                if (Brain.get(this).hasModel()) {
                    think(text, a);
                } else if (a.kind == Bot.UNKNOWN && !offeredModel) {
                    // Once per talk: a question only a model could answer is the moment to mention it.
                    offeredModel = true;
                    needModel(a.text + " " + getString(R.string.brain_no_model_hint));
                } else {
                    answer(a.text, a.trace);
                }
                break;
            default:
                if (!a.text.isEmpty()) {
                    answer(a.text, a.trace);
                }
                break;
        }
    }

    /** The picture made small enough for the model to look at quickly: at most 768 px, JPEG. */
    private byte[] shrink(android.net.Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            int side = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (side / (sample * 2) >= 768) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
            d.inSampleSize = sample;
            android.graphics.Bitmap b;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                b = android.graphics.BitmapFactory.decodeStream(in, null, d);
            }
            if (b == null) {
                return null;
            }
            b = upright(b, uri);
            float k = 768f / Math.max(b.getWidth(), b.getHeight());
            if (k < 1f) {
                b = android.graphics.Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k),
                        Math.round(b.getHeight() * k), true);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            return out.toByteArray();
        } catch (Exception e) {
            Diag.log(this, "talk: picture not read", e);
            return null;
        }
    }

    /**
     * Turns a picture the way it was held. A camera writes the pixels as the
     * sensor sees them and only notes the turn in the file's tags; drawn as is,
     * a portrait shot lies on its side, for the eye and for the model alike.
     */
    private android.graphics.Bitmap upright(android.graphics.Bitmap b, android.net.Uri uri) {
        int turn;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return b;
            }
            turn = new android.media.ExifInterface(in).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return b;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        switch (turn) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1, 1);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setScale(1, -1);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                m.setRotate(90);
                m.postScale(-1, 1);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                m.setRotate(-90);
                m.postScale(-1, 1);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(-90);
                break;
            default:
                return b;
        }
        android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        Diag.log(this, "talk: picture turned, tag " + turn);
        return r;
    }

    /** Copies a camera shot whole into the talk's cache; returns the file, or null. */
    private String keepShot(android.net.Uri uri) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "talk");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "shot-" + System.currentTimeMillis() + ".jpg");
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                java.nio.file.Files.copy(in, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return f.getPath();
        } catch (Exception e) {
            Diag.log(this, "camera: shot not kept", e);
            return null;
        }
    }

    /**
     * Puts a camera shot into the gallery, tags and all, under the app's own
     * album. On older phones, where the gallery cannot be written without a
     * storage permission the app does not ask for, it goes to the Hora folder.
     */
    private void saveShot(final Line l, final TextView label) {
        label.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String name = "Hora " + new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT)
                        .format(new java.util.Date()) + ".jpg";
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues v = new android.content.ContentValues();
                        v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_PICTURES + "/Hora");
                        v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                        android.net.Uri where = getContentResolver().insert(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        if (where != null) {
                            try (java.io.OutputStream out = getContentResolver().openOutputStream(where)) {
                                java.nio.file.Files.copy(new java.io.File(l.shot).toPath(), out);
                            }
                            v.clear();
                            v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                            getContentResolver().update(where, v, null, null);
                            ok = true;
                        }
                    } else if (HoraFolder.tree(TalkActivity.this) != null) {
                        android.net.Uri dir = HoraFolder.sub(TalkActivity.this, getString(R.string.folder_photos));
                        android.net.Uri file = android.provider.DocumentsContract.createDocument(
                                getContentResolver(), dir, "image/jpeg", name);
                        if (file != null) {
                            try (java.io.OutputStream out = getContentResolver().openOutputStream(file)) {
                                java.nio.file.Files.copy(new java.io.File(l.shot).toPath(), out);
                            }
                            HoraFolder.announce(TalkActivity.this, file);
                            ok = true;
                        }
                    }
                } catch (Exception e) {
                    Diag.log(TalkActivity.this, "camera: shot not saved", e);
                }
                final boolean done = ok;
                Diag.mark(TalkActivity.this, done ? "camera: shot saved" : "camera: shot not saved");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            l.saved = true;
                            label.setText(R.string.talk_shot_saved);
                            label.setTextColor(Palette.MUTED);
                        } else {
                            label.setEnabled(true);
                            label.setText(Build.VERSION.SDK_INT < 29 && HoraFolder.tree(TalkActivity.this) == null
                                    ? R.string.talk_shot_need_folder : R.string.talk_shot_failed);
                        }
                    }
                });
            }
        }, "talk-save-shot").start();
    }

    /** A request with a picture: always for the model's eyes, when it has them. */
    private void look(String text, byte[] jpeg, String shotFile) {
        Line mine = new Line(true, text, "");
        mine.shot = shotFile;
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "talk");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "picture-" + System.currentTimeMillis() + ".jpg");
            java.nio.file.Files.write(f.toPath(), jpeg);
            mine.image = f.getPath();
        } catch (Exception e) {
            Diag.log(this, "talk: picture not kept", e);
        }
        add(mine);
        Brain b = Brain.get(this);
        if (!b.hasModel()) {
            needModel(getString(R.string.brain_no_model));
            return;
        }
        if (!b.has(Brain.SLOT_VISION)) {
            needModel(getString(R.string.brain_no_vision));
            return;
        }
        if (b.visionRefused()) {
            // Another model is in place and the photo part does not fit it.
            answer(getString(R.string.brain_custom_no_photo), "");
            return;
        }
        think(text, null, jpeg);
    }

    /** How many earlier lines of the talk the model sees, so it keeps the thread. */

    /**
     * Free talk: a placeholder line while the model thinks, then its reply in
     * the voice's own manner. If the model fails, the plain answer stands.
     */
    private void think(final String said, final Bot.Answer fallback) {
        think(said, fallback, null);
    }

    private void think(final String said, final Bot.Answer fallback, final byte[] jpeg) {
        final TextView waiting = bubbleText(new Line(false, getString(R.string.talk_thinking), ""));
        final View holder = (View) waiting.getParent();
        lines.addView(holder);
        toBottom();
        final int v = voice;
        // A picture changes the task: say what is in it now, plainly, with what is written on it.
        // The book-talk rules (one or two sentences, no facts about the world) would leave the
        // answer vague, so the look rules are added after them and take precedence.
        final String system = jpeg == null ? Brain.persona(this, v)
                : Brain.persona(this, v) + " " + SpeechLanguage.resources(this).getString(R.string.brain_look_rules);
        final int tokens = jpeg == null ? Brain.replyTokens(this) : Math.max(Brain.replyTokens(this), 160);
        final java.util.List<String[]> turns = new java.util.ArrayList<String[]>();
        int from = Math.max(0, talk.size() - Brain.remembered(this));
        for (int i = from; i < talk.size(); i++) {
            Line l = talk.get(i);
            turns.add(new String[] {l.mine ? "user" : "assistant", l.text});
        }
        // The model needs the talk to open with the user and to take turns.
        while (!turns.isEmpty() && !"user".equals(turns.get(0)[0])) {
            turns.remove(0);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String reply;
                try {
                    reply = Brain.get(TalkActivity.this).ask(system, turns, tokens, jpeg).text;
                } catch (Exception e) {
                    Diag.log(TalkActivity.this, "brain: talk failed", e);
                    reply = null;
                }
                final String text = reply;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        lines.removeView(holder);
                        if (text != null && !text.isEmpty()) {
                            answer(text, "");
                        } else if (fallback != null) {
                            answer(fallback.text, fallback.trace);
                        } else {
                            answer(getString(R.string.lab_brain_failed, "?"), "");
                        }
                    }
                });
            }
        }, "talk-brain").start();
    }

    /**
     * A question about the world: the reference finds the article. With the model, it answers
     * from the article's text and nothing else; without it, the article's opening is read as it
     * is. Either way the source goes under the answer, and a tap opens it.
     */
    private void lookUp(final String question, final Bot.Answer fallback) {
        final TextView waiting = bubbleText(new Line(false, getString(R.string.wiki_looking), ""));
        final View holder = (View) waiting.getParent();
        lines.addView(holder);
        toBottom();
        final int v = voice;
        final boolean model = Brain.get(this).hasModel();
        final android.content.res.Resources speech = SpeechLanguage.resources(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String said;
                Wiki.Article art = null;
                try {
                    art = Wiki.look(TalkActivity.this, question);
                    if (art == null) {
                        said = speech.getString(R.string.wiki_nothing);
                    } else if (model) {
                        String system = Brain.persona(TalkActivity.this, v) + " "
                                + speech.getString(R.string.brain_wiki_rules, art.title) + "\n\n" + art.body;
                        java.util.List<String[]> turns = new java.util.ArrayList<String[]>();
                        turns.add(new String[] {"user", question});
                        said = Brain.get(TalkActivity.this).ask(system, turns,
                                Math.max(Brain.replyTokens(TalkActivity.this), 160), null).text;
                        if (said == null || said.trim().isEmpty()) {
                            said = art.opening;
                        }
                    } else {
                        said = art.opening;
                    }
                } catch (Exception e) {
                    Diag.log(TalkActivity.this, "reference: out of reach", e);
                    said = speech.getString(R.string.wiki_offline);
                }
                final String text = said;
                final Wiki.Article source = art;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        lines.removeView(holder);
                        Line l = new Line(false, Ui.plain(text).toString(),
                                source == null ? "" : getString(R.string.wiki_source, source.title));
                        l.link = source == null ? null : source.link;
                        add(l);
                        VoiceService.answer(TalkActivity.this, text, v);
                    }
                });
            }
        }, "talk-reference").start();
    }

    private boolean saveWanted;

    private static final int TAKE_PHOTO = 82;
    private static final String SHOT_WAITING = "talk_shot_waiting";

    /**
     * The phone's camera, writing into the app's own slot in the cache, so the
     * picture never reaches the gallery. No camera permission is declared: a
     * declared but refused one would forbid calling the camera at all.
     */
    private void takePhoto() {
        PhotoSlot.sweep(this);
        Intent shot = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, PhotoSlot.uri())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shot.setClipData(android.content.ClipData.newRawUri("", PhotoSlot.uri()));
        try {
            // Written at once: the system may close this window while the camera is open.
            getSharedPreferences("talk", MODE_PRIVATE).edit().putBoolean(SHOT_WAITING, true).commit();
            startActivityForResult(shot, TAKE_PHOTO);
        } catch (android.content.ActivityNotFoundException e) {
            getSharedPreferences("talk", MODE_PRIVATE).edit().putBoolean(SHOT_WAITING, false).commit();
            Diag.log(this, "camera: no camera app");
        }
    }

    /** Picks up a picture the camera left, even if this window was rebuilt meanwhile. */
    private void collectShot() {
        android.content.SharedPreferences sp = getSharedPreferences("talk", MODE_PRIVATE);
        if (!sp.getBoolean(SHOT_WAITING, false)) {
            return;
        }
        java.io.File f = PhotoSlot.file(this);
        // The size of the file itself, not what an index says while the camera is still closing.
        if (f.length() > 0) {
            sp.edit().putBoolean(SHOT_WAITING, false).commit();
            attachImage(PhotoSlot.uri(), true);
        }
    }

    /** The phone's photo picker: recent shots and albums, no folders and no permission. */
    private void pickPhoto() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivityForResult(new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES), PICK_IMAGE);
                return;
            } catch (android.content.ActivityNotFoundException e) {
                Diag.log(this, "talk: no photo picker, files instead");
            }
        }
        pickFile();
    }

    private void pickFile() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"), PICK_IMAGE);
    }

    /** A picture handed over by another app through sharing. */
    static final String EXTRA_PHOTO = "photo";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeShared(intent);
    }

    private void takeShared(Intent intent) {
        android.net.Uri uri = intent == null ? null : (android.net.Uri) intent.getParcelableExtra(EXTRA_PHOTO);
        if (uri != null) {
            intent.removeExtra(EXTRA_PHOTO);
            attachImage(uri);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            attachImage(data.getData());
            return;
        }
        if (request == TAKE_PHOTO) {
            if (result != RESULT_OK) {
                getSharedPreferences("talk", MODE_PRIVATE).edit().putBoolean(SHOT_WAITING, false).commit();
                Diag.log(this, "camera: closed without a picture");
            } else if (PhotoSlot.file(this).length() == 0 && data != null && data.getData() != null) {
                // Some cameras keep the picture themselves and hand back its address.
                getSharedPreferences("talk", MODE_PRIVATE).edit().putBoolean(SHOT_WAITING, false).commit();
                Diag.log(this, "camera: picture came back by its own address");
                attachImage(data.getData(), true);
            }
            return;
        }
        if (request == HoraFolder.PICK) {
            boolean ok = result == RESULT_OK && HoraFolder.accept(this, data);
            if (ok && saveWanted) {
                saveWanted = false;
                saveTalk();
            }
        }
    }

    /** Shrinks a picture off the main thread and shows it above the field, waiting for a question. */
    private void attachImage(final android.net.Uri uri) {
        attachImage(uri, false);
    }

    /**
     * The same, and for a camera shot the full picture is copied aside: the
     * camera slot is emptied by the next shot, while the talk may want to keep this one.
     */
    private void attachImage(final android.net.Uri uri, final boolean fromCamera) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] jpeg = shrink(uri);
                final String kept = fromCamera && jpeg != null ? keepShot(uri) : null;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (jpeg == null) {
                            Diag.log(TalkActivity.this, "talk: picture unreadable");
                            return;
                        }
                        pending = jpeg;
                        pendingShot = kept;
                        attachedThumb.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0,
                                jpeg.length));
                        attached.setVisibility(View.VISIBLE);
                        showGo();
                    }
                });
            }
        }, "talk-image").start();
    }

    /**
     * The talk so far as a text file in Hora's folder: who and when at the
     * top, a title, then the lines. The title is the model's few words for
     * the talk when it is there, otherwise the first words of the first request.
     */
    private void saveTalk() {
        if (HoraFolder.tree(this) == null) {
            saveWanted = true;
            answer(getString(R.string.talk_folder_ask), "");
            HoraFolder.ask(this);
            return;
        }
        final java.util.List<Line> lines = new java.util.ArrayList<Line>();
        for (Line l : talk) {
            // The request to save is not part of what is saved.
            lines.add(l);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).mine) {
            lines.remove(lines.size() - 1);
        }
        final int v = voice;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String title = titleFor(lines, v);
                java.util.Date now = new java.util.Date();
                StringBuilder sb = new StringBuilder();
                String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG,
                        java.text.DateFormat.SHORT).format(now);
                sb.append(getString(R.string.talk_file_head, Cast.name(TalkActivity.this, v), when, title))
                        .append("\n\n");
                for (Line l : lines) {
                    sb.append("\u2014 ").append(l.mine ? getString(R.string.talk_you) : Cast.name(TalkActivity.this, v))
                            .append(": ").append(l.text).append("\n\n");
                }
                String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.ROOT).format(now);
                final String name = stamp + " \u2014 " + title.replaceAll("[\\\\/:*?\"<>|]", "") + ".txt";
                String failed = null;
                android.net.Uri where = null;
                try {
                    where = HoraFolder.writeText(TalkActivity.this, getString(R.string.folder_talks), name,
                            sb.toString());
                } catch (Exception e) {
                    failed = String.valueOf(e.getMessage());
                    Diag.log(TalkActivity.this, "talk: save failed", e);
                }
                final String error = failed;
                final android.net.Uri file = where;
                final String shownTitle = title;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null || file == null) {
                            answer(getString(R.string.talk_save_failed, error), "");
                            return;
                        }
                        Line done = new Line(false, getString(R.string.talk_saved, shownTitle), name);
                        done.file = file.toString();
                        add(done);
                        VoiceService.answer(TalkActivity.this, done.text, voice);
                    }
                });
            }
        }, "talk-save").start();
    }

    /** A few words naming the talk: from the model when it can, else the first request. */
    private String titleFor(java.util.List<Line> lines, int v) {
        String first = "";
        for (Line l : lines) {
            if (l.mine) {
                first = l.text;
                break;
            }
        }
        if (Brain.get(this).hasModel() && !lines.isEmpty()) {
            try {
                java.util.List<String[]> turns = new java.util.ArrayList<String[]>();
                StringBuilder all = new StringBuilder();
                for (Line l : lines) {
                    all.append(l.mine ? getString(R.string.talk_you) : Cast.name(this, v)).append(": ")
                            .append(l.text).append("\n");
                }
                turns.add(new String[] {"user", all.toString() + "\n" + getString(R.string.brain_title_prompt)});
                String t = Brain.get(this).ask(getString(R.string.brain_title_prompt), turns, 20).text;
                t = t.replaceAll("[\"\u00ab\u00bb\u201c\u201d.]+", "").replace('\n', ' ').trim();
                if (t.length() > 2 && t.length() <= 60) {
                    return t;
                }
            } catch (Exception e) {
                Diag.log(this, "talk: no title from the model", e);
            }
        }
        String[] words = first.replaceAll("[^\\p{L}\\p{N} ]+", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length && i < 6; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.length() > 0 ? sb.toString() : getString(R.string.talk_title_fallback);
    }

    private void share(String uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.talk_share)));
    }

    private void weather() {
        if (!Weather.hasPlace(this)) {
            answer(Weather.line(this, "ask." + voice), "");
            startActivity(new Intent(this, PlaceActivity.class));
            return;
        }
        final int teller = voice;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String told = Weather.report(TalkActivity.this, teller);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        answer(told != null ? told : Weather.line(TalkActivity.this, "fail"), "");
                    }
                });
            }
        }, "weather").start();
    }

    private void answer(String text, String trace) {
        add(new Line(false, Ui.plain(text).toString(), trace));
        VoiceService.answer(this, text, voice);
    }

    /** Says what is missing and offers to fetch it, on the line itself. */
    private void needModel(String text) {
        Line l = new Line(false, Ui.plain(text).toString(), "");
        l.needsModel = true;
        add(l);
        VoiceService.answer(this, text, voice);
    }

    private void add(Line l) {
        talk.add(l);
        while (talk.size() > KEPT) {
            talk.remove(0);
            if (lines.getChildCount() > 0) {
                lines.removeViewAt(0);
            }
        }
        lines.addView(bubble(l));
        toBottom();
    }

    private void toBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** A line's bubble, returned as its text view (its parent is the holder to add). */
    /** Copy or share one line of the talk. */
    private void lineSheet(final String words) {
        TextView share = Kit.link(this, getString(R.string.talk_share), null);
        LinearLayout lesser = Ui.column(this);
        lesser.addView(share);
        TextView quote = Ui.text(this, words, 15, Palette.MUTED);
        quote.setMaxLines(6);
        quote.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final android.app.Dialog d = Kit.sheet(this, getString(R.string.talk_line_title), quote,
                getString(R.string.talk_copy), new Runnable() {
                    @Override
                    public void run() {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Hora", words));
                        // Newer systems confirm a copy themselves; older ones get a word from us.
                        if (android.os.Build.VERSION.SDK_INT < 33) {
                            android.widget.Toast.makeText(TalkActivity.this, R.string.talk_copied,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }, lesser);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, words), getString(R.string.talk_share)));
            }
        });
        d.show();
    }

    private TextView bubbleText(Line l) {
        View holder = bubble(l);
        return (TextView) ((LinearLayout) holder).getChildAt(0);
    }

    /** Her words on a plate at the left in the display face; yours smaller at the right. */
    private View bubble(Line l) {
        LinearLayout holder = Ui.column(this);
        holder.setGravity(l.mine ? Gravity.END : Gravity.START);
        TextView t = l.mine ? Ui.text(this, l.text, 15, Palette.MUTED) : Ui.title(this, l.text, 20);
        if (!l.mine) {
            t.setLineSpacing(0, 1.2f);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(l.mine ? Palette.RAISED : Palette.SURFACE);
        float big = dp(l.mine ? 18 : 20);
        float small = dp(6);
        bg.setCornerRadii(l.mine ? new float[] {big, big, big, big, small, small, big, big}
                : new float[] {big, big, big, big, big, big, small, small});
        t.setBackground(bg);
        t.setPadding(dp(l.mine ? 14 : 16), dp(l.mine ? 9 : 12), dp(l.mine ? 14 : 16), dp(l.mine ? 9 : 12));
        t.setMaxWidth(getResources().getDisplayMetrics().widthPixels * (l.mine ? 78 : 86) / 100);
        if (!l.mine) {
            final String said = l.text;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    VoiceService.answer(TalkActivity.this, said, voice);
                }
            });
        }
        // Any line, hers or yours: a long press offers to copy it or send it on.
        final String words = l.text;
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                lineSheet(words);
                return true;
            }
        });
        if (l.image != null) {
            ImageView pic = new ImageView(this);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setBackground(Ui.round(Palette.RAISED, 16, this));
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(l.image);
            if (bm != null) {
                pic.setImageBitmap(bm);
                LinearLayout.LayoutParams pp = Ui.lp(dp(200), dp(Math.max(80,
                        Math.min(260, 200f * bm.getHeight() / Math.max(1, bm.getWidth())))));
                pp.bottomMargin = dp(6);
                holder.addView(pic, pp);
                if (l.shot != null) {
                    final Line shotLine = l;
                    final TextView keep = Kit.link(this, getString(l.saved ? R.string.talk_shot_saved
                            : R.string.talk_shot_save), null);
                    keep.setTextSize(14);
                    keep.setPadding(dp(4), 0, dp(4), dp(10));
                    if (l.saved) {
                        keep.setTextColor(Palette.MUTED);
                    } else {
                        keep.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (Build.VERSION.SDK_INT < 29 && HoraFolder.tree(TalkActivity.this) == null) {
                                    HoraFolder.ask(TalkActivity.this);
                                } else if (!shotLine.saved) {
                                    saveShot(shotLine, keep);
                                }
                            }
                        });
                    }
                    holder.addView(keep, Ui.lp(Ui.WRAP, Ui.WRAP));
                }
            }
        }
        holder.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP));
        if (l.needsModel && !(Brain.get(this).hasModel() && Brain.get(this).has(Brain.SLOT_VISION))) {
            TextView get = Ui.pill(this, getString(R.string.brain_get_short), Palette.ACCENT, 0, Palette.ON_ACCENT, true);
            get.setPadding(dp(18), 0, dp(18), 0);
            get.setGravity(Gravity.CENTER);
            get.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(TalkActivity.this, BrainActivity.class)
                            .putExtra(BrainActivity.EXTRA_GET, true));
                }
            });
            LinearLayout.LayoutParams gp = Ui.lp(Ui.WRAP, dp(44));
            gp.topMargin = dp(8);
            holder.addView(get, gp);
        }
        if (l.file != null) {
            final String uri = l.file;
            LinearLayout card = Ui.row(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(Ui.round(Palette.RAISED, 16, this));
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            TextView fname = Ui.text(this, l.trace, 14, Palette.INK);
            fname.setSingleLine(true);
            fname.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(fname, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = Kit.link(this, getString(R.string.talk_share), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    share(uri);
                }
            });
            LinearLayout.LayoutParams gp = Ui.lp(Ui.WRAP, Ui.WRAP);
            gp.leftMargin = dp(12);
            card.addView(go, gp);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    getResources().getDisplayMetrics().widthPixels * 86 / 100, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(6);
            holder.addView(card, cp);
        } else if (!l.trace.isEmpty()) {
            TextView tr = Ui.text(this, "\u25b8 " + l.trace, 12, Palette.ACCENT_TEXT);
            tr.setTypeface(Palette.bodyStrong(this));
            tr.setPadding(dp(8), dp(6), 0, 0);
            if (l.link != null) {
                final String link = l.link;
                tr.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)));
                        } catch (android.content.ActivityNotFoundException e) {
                            Diag.log(TalkActivity.this, "reference: nothing opens the link", e);
                        }
                    }
                });
            }
            holder.addView(tr, Ui.lp(Ui.WRAP, Ui.WRAP));
        }
        LinearLayout.LayoutParams hp = Ui.lp(Ui.MATCH, Ui.WRAP);
        hp.topMargin = dp(12);
        holder.setLayoutParams(hp);
        return holder;
    }
}
