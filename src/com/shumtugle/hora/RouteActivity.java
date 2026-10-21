package com.shumtugle.hora;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The route, apart from the notifications: what is said of a navigator's way (the turns, the time
 * left), and where it may be heard. Turned on and off by itself; only Hora's quiet hours, which are
 * one set for everything, silence it.
 */
public final class RouteActivity extends Activity {
    private int built;
    private AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout state;
    private View streetRow;
    private View speakerRow;
    private View sampleRow;

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            showState();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            showState();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        built = Ui.prepare(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        LinearLayout list = Kit.room(this, getString(R.string.route_title));
        list.addView(Kit.lead(this, getString(R.string.route_lead)));

        state = Ui.column(this);
        list.addView(state, Kit.wide());

        list.addView(Kit.section(this, getString(R.string.route_what)));
        LinearLayout what = Kit.plate(this);
        what.addView(Kit.rowToggle(this, getString(R.string.nav_turns), getString(R.string.nav_turns_sub),
                Prefs.navWhisperShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavWhisper(RouteActivity.this, on);
                        Diag.mark(RouteActivity.this, "nav: turn hints " + (on ? "on" : "off"));
                        showRows();
                    }
                }));
        what.addView(Kit.divider(this));
        streetRow = Kit.rowToggle(this, getString(R.string.nav_street), getString(R.string.nav_street_sub),
                Prefs.navStreetShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavStreet(RouteActivity.this, on);
                    }
                });
        what.addView(streetRow);
        what.addView(Kit.divider(this));
        what.addView(Kit.rowToggle(this, getString(R.string.nav_eta), getString(R.string.nav_eta_sub),
                Prefs.navEtaShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavEta(RouteActivity.this, on);
                        Diag.mark(RouteActivity.this, "nav: time left " + (on ? "on" : "off"));
                        showRows();
                    }
                }));
        what.addView(Kit.divider(this));
        speakerRow = Kit.rowToggle(this, getString(R.string.nav_speaker), getString(R.string.nav_speaker_sub),
                Prefs.navSpeakerShared(this), new Kit.Flip() {
                    @Override
                    public void flipped(boolean on) {
                        Prefs.setNavSpeaker(RouteActivity.this, on);
                        Diag.mark(RouteActivity.this, "nav: speaker " + (on ? "allowed" : "not allowed"));
                        showState();
                    }
                });
        what.addView(speakerRow);
        list.addView(what, Kit.wide());

        LinearLayout try1 = Kit.plate(this);
        sampleRow = Kit.rowNav(this, getString(R.string.nav_sample), "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sample();
            }
        });
        try1.addView(sampleRow);
        list.addView(try1, Kit.below(this, 20));

        TextView note = Ui.text(this, getString(R.string.route_note), 13, Palette.MUTED);
        note.setLineSpacing(0, 1.25f);
        list.addView(note, Kit.below(this, 12));
        showRows();
        audio.registerAudioDeviceCallback(devices, main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.stale(this, built)) {
            return;
        }
        showState();
    }

    @Override
    protected void onDestroy() {
        if (audio != null) {
            audio.unregisterAudioDeviceCallback(devices);
        }
        super.onDestroy();
    }

    /** Access to the notifications, and where the sound would go now. */
    private void showState() {
        state.removeAllViews();
        boolean granted = Herald.granted(this);
        LinearLayout plate = Kit.plate(this);
        plate.addView(Kit.status(this, getString(granted ? R.string.herald_access_on : R.string.herald_access_off),
                granted ? Kit.Mood.FINE : Kit.Mood.TROUBLE));
        int where = where(this, audio);
        plate.addView(Kit.status(this, getString(where == EARS ? R.string.route_sound_ears
                : where == SPEAKER ? R.string.route_sound_speaker : R.string.route_sound_none),
                where == NONE ? Kit.Mood.UNKNOWN : Kit.Mood.FINE));
        state.addView(plate, Kit.wide());
        if (!granted) {
            state.addView(Kit.primary(this, getString(R.string.herald_grant), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Herald.openAccess(RouteActivity.this);
                }
            }), Kit.below(this, 16));
        }
    }

    static final int NONE = 0;
    static final int EARS = 1;
    static final int SPEAKER = 2;

    /** Where a route hint would be heard now. */
    static int where(Context c, AudioManager audio) {
        if (Earpiece.find(audio) != null) {
            return EARS;
        }
        return Prefs.navSpeakerShared(c) ? SPEAKER : NONE;
    }

    /** The street only matters with the turns, the speaker with anything said at all; they dim rather than hide. */
    private void showRows() {
        boolean turns = Prefs.navWhisperShared(this);
        streetRow.setAlpha(turns ? 1f : 0.4f);
        streetRow.setEnabled(turns);
        boolean any = Prefs.routeShared(this);
        speakerRow.setAlpha(any ? 1f : 0.4f);
        speakerRow.setEnabled(any);
    }

    /** An early hint for a right turn, said where a hint would be heard; the row says why when it cannot be. */
    private void sample() {
        if (!Herald.granted(this)) {
            Kit.setValue(sampleRow, getString(R.string.nav_sample_no_access));
            return;
        }
        if (where(this, audio) == NONE) {
            Kit.setValue(sampleRow, getString(R.string.nav_sample_no_ears));
            return;
        }
        Kit.setValue(sampleRow, "");
        sendBroadcast(new Intent(Herald.ACTION_ROUTE_SAMPLE).setPackage(getPackageName()));
    }
}
