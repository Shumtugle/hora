package com.shumtugle.hora;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Finding the place whose sky to tell: a name typed, the places of that name
 * listed with their region and country, one chosen. Namesakes stand apart,
 * so a town abroad is not mistaken for a capital. Or the phone is asked once,
 * roughly, where it is.
 */
public final class PlaceActivity extends Activity {
    private int built;
    private EditText query;
    private LinearLayout results;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        built = Ui.prepare(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.addView(Ui.header(this, getString(R.string.place_title), false), Ui.lp(Ui.MATCH, dp(48)));

        LinearLayout line = Ui.row(this);
        line.setBackground(Ui.round(Palette.SURFACE, 26, this));
        line.setPadding(dp(18), dp(6), dp(6), dp(6));
        query = new EditText(this);
        Ui.field(query);
        query.setHint(R.string.place_hint);
        query.setHintTextColor(Palette.HINT);
        query.setTextColor(Palette.INK);
        query.setTextSize(16);
        query.setTypeface(Palette.body(this));
        query.setBackground(null);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                search();
                return true;
            }
        });
        line.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1f));
        ImageView go = Ui.iconButton(this, R.drawable.ic_search, Palette.ACCENT, 44, getString(R.string.place_find));
        go.setImageTintList(android.content.res.ColorStateList.valueOf(Palette.ON_ACCENT));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search();
            }
        });
        line.addView(go);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(16);
        root.addView(line, lp);
        TextView byPhone = Kit.link(this, getString(R.string.place_by_phone), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                locate();
            }
        });
        LinearLayout.LayoutParams bp = Ui.lp(Ui.WRAP, Ui.WRAP);
        bp.topMargin = dp(6);
        root.addView(byPhone, bp);

        status = Ui.text(this, "", 13, Palette.MUTED);
        status.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams sp = Ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = dp(12);
        root.addView(status, sp);

        ScrollView scroll = new ScrollView(this);
        results = Ui.column(this);
        scroll.addView(results);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        rp.topMargin = dp(8);
        root.addView(scroll, rp);
        setContentView(root);
        query.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.stale(this, built);
    }

    private void search() {
        final String name = query.getText().toString().trim();
        if (name.isEmpty()) {
            return;
        }
        status.setText(R.string.place_searching);
        results.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Weather.Place> found = null;
                try {
                    found = Weather.search(PlaceActivity.this, name);
                } catch (Exception e) {
                    Diag.log(PlaceActivity.this, "place: search failed", e);
                }
                final List<Weather.Place> shown = found;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        show(shown);
                    }
                });
            }
        }, "place-search").start();
    }

    private void show(List<Weather.Place> found) {
        results.removeAllViews();
        if (found == null) {
            status.setText(Weather.line(this, "fail"));
            return;
        }
        if (found.isEmpty()) {
            status.setText(Weather.line(this, "nowhere"));
            return;
        }
        status.setText("");
        for (final Weather.Place p : found) {
            LinearLayout row = Ui.column(this);
            row.setBackground(Ui.pressable(Ui.round(Palette.SURFACE, 18, this)));
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setClickable(true);
            row.addView(Ui.title(this, p.name, 20));
            if (!p.region.isEmpty()) {
                TextView r = Ui.text(this, p.region, 13, Palette.MUTED);
                LinearLayout.LayoutParams rp = Ui.lp(Ui.MATCH, Ui.WRAP);
                rp.topMargin = dp(4);
                row.addView(r, rp);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Weather.setPlace(PlaceActivity.this, p.name, p.lat, p.lon);
                    setResult(RESULT_OK);
                    finish();
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
            lp.topMargin = dp(8);
            results.addView(row, lp);
        }
    }

    // The phone's own idea of where it is: rough, asked once, never kept watching.

    private static final String LOCATION = "android.permission.ACCESS_COARSE_LOCATION";
    private static final int ASK_LOCATION = 41;
    private static final long LOCATE_MS = 8000;
    private boolean locating;

    private void locate() {
        if (checkSelfPermission(LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {LOCATION}, ASK_LOCATION);
            return;
        }
        status.setText(R.string.place_searching);
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        android.location.Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best == null && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                    && android.os.Build.VERSION.SDK_INT >= 30) {
                // A fresh answer, but never waited for longer than a few seconds.
                final android.os.CancellationSignal stop = new android.os.CancellationSignal();
                locating = true;
                lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, stop, getMainExecutor(),
                        new java.util.function.Consumer<android.location.Location>() {
                            @Override
                            public void accept(android.location.Location l) {
                                if (locating) {
                                    locating = false;
                                    placed(l);
                                }
                            }
                        });
                status.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (locating) {
                            locating = false;
                            stop.cancel();
                            placed(null);
                        }
                    }
                }, LOCATE_MS);
                return;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            best = null;
        }
        placed(best);
    }

    private void placed(final android.location.Location l) {
        if (l == null) {
            // The phone kept silent: the search by name is the way on.
            status.setText(Weather.line(this, "nolocation"));
            query.requestFocus();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = "";
                try {
                    if (android.location.Geocoder.isPresent()) {
                        List<android.location.Address> a = new android.location.Geocoder(PlaceActivity.this,
                                SpeechLanguage.locale()).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                        if (a != null && !a.isEmpty() && a.get(0).getLocality() != null) {
                            name = a.get(0).getLocality();
                        }
                    }
                } catch (Exception ignored) {
                    // Without a name the place is still known by where it lies.
                }
                Weather.setPlace(PlaceActivity.this, name, l.getLatitude(), l.getLongitude());
                Diag.mark(PlaceActivity.this, "place: taken from the phone");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        }, "place-phone").start();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request != ASK_LOCATION) {
            return;
        }
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locate();
        } else {
            placed(null);
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
