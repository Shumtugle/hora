package com.shumtugle.hora;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The sky over the chosen place, asked for over the network at most every
 * twenty minutes, and told by a voice. The place is either picked from a
 * search by name or taken once from the phone; nothing is asked of the phone
 * behind the listener's back. The addresses asked live in the bundle.
 */
final class Weather {
    private static final String FILE = "weather";
    private static final String NAME = "place_name";
    private static final String LAT = "place_lat";
    private static final String LON = "place_lon";
    private static final String SAID = "said_";
    private static final long FRESH_MS = 20 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;

    private static WeatherTalk.Facts cached;
    private static long cachedAt;
    private static String cachedFor = "";
    private static WeatherTalk talk;

    /** A found place: a name, where it lies, and words that tell it apart from its namesakes. */
    static final class Place {
        final String name;
        final String region;
        final double lat;
        final double lon;

        Place(String name, String region, double lat, double lon) {
            this.name = name;
            this.region = region;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Weather() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean hasPlace(Context c) {
        return sp(c).contains(LAT);
    }

    static String placeName(Context c) {
        return sp(c).getString(NAME, "");
    }

    static void setPlace(Context c, String name, double lat, double lon) {
        sp(c).edit().putString(NAME, name == null ? "" : name)
                .putString(LAT, String.valueOf(lat)).putString(LON, String.valueOf(lon)).apply();
        cached = null;
    }

    /** The report for a voice, or null when the sky could not be reached. Call off the main thread. */
    static synchronized String report(Context c, int voice) {
        WeatherTalk.Facts f = facts(c);
        if (f == null) {
            return null;
        }
        try {
            if (talk == null) {
                talk = new WeatherTalk(c.getAssets().open("talk/weather.txt"));
            }
        } catch (IOException e) {
            return null;
        }
        final SharedPreferences sp = sp(c);
        return talk.say(f, voice, new Random(), new WeatherTalk.Memory() {
            @Override
            public int last(String key) {
                return sp.getInt(SAID + key, -1);
            }

            @Override
            public void used(String key, int index) {
                sp.edit().putInt(SAID + key, index).apply();
            }
        });
    }

    /** One line from the talk file for a key, picked at random; empty if there is none. */
    static synchronized String line(Context c, String key) {
        try {
            if (talk == null) {
                talk = new WeatherTalk(c.getAssets().open("talk/weather.txt"));
            }
            return talk.any(key, new Random());
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The weather in a few words from what was fetched lately, without going
     * to the network: degrees and sky, then degrees alone. Null when nothing is fresh.
     */
    static synchronized String[] brief(Context c) {
        if (cached == null || System.currentTimeMillis() - cachedAt >= FRESH_MS) {
            return null;
        }
        line(c, "");
        return talk == null ? null : talk.brief(cached);
    }

    private static WeatherTalk.Facts facts(Context c) {
        String lat = sp(c).getString(LAT, "");
        String lon = sp(c).getString(LON, "");
        if (lat.isEmpty()) {
            return null;
        }
        String key = lat + "," + lon;
        long now = System.currentTimeMillis();
        if (cached != null && key.equals(cachedFor) && now - cachedAt < FRESH_MS) {
            return cached;
        }
        try {
            String url = asset(c, "talk/forecast-url.txt").replace("%lat", lat).replace("%lon", lon);
            WeatherTalk.Facts f = parse(new JSONObject(get(url)));
            cached = f;
            cachedAt = now;
            cachedFor = key;
            return f;
        } catch (Exception e) {
            Diag.log(c, "weather: no answer", e);
            return cached != null && key.equals(cachedFor) ? cached : null;
        }
    }

    static WeatherTalk.Facts parse(JSONObject o) throws org.json.JSONException {
        WeatherTalk.Facts f = new WeatherTalk.Facts();
        JSONObject now = o.getJSONObject("current");
        f.temp = (int) Math.round(now.getDouble("temperature_2m"));
        f.feels = (int) Math.round(now.optDouble("apparent_temperature", f.temp));
        f.wind = (int) Math.round(now.optDouble("wind_speed_10m", 0));
        f.code = now.optInt("weather_code", 0);
        String time = now.optString("time", "");
        f.hour = time.length() >= 13 ? Integer.parseInt(time.substring(11, 13)) : 12;
        f.minutes = f.hour * 60 + (time.length() >= 16 ? Integer.parseInt(time.substring(14, 16)) : 0);
        // The answer's "now" is rounded to a quarter of an hour; the clock at the place is exact.
        if (o.has("utc_offset_seconds")) {
            java.util.Calendar there = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            there.setTimeInMillis(System.currentTimeMillis() + o.getLong("utc_offset_seconds") * 1000L);
            f.hour = there.get(java.util.Calendar.HOUR_OF_DAY);
            f.minutes = f.hour * 60 + there.get(java.util.Calendar.MINUTE);
        }
        f.isDay = now.optInt("is_day", f.hour >= 7 && f.hour < 19 ? 1 : 0) == 1;
        JSONArray hours = o.getJSONObject("hourly").getJSONArray("weather_code");
        int n = Math.max(0, hours.length() - 1);
        f.next = new int[n];
        for (int i = 0; i < n; i++) {
            f.next[i] = hours.optInt(i + 1, f.code);
        }
        JSONObject d = o.getJSONObject("daily");
        f.high = (int) Math.round(d.getJSONArray("temperature_2m_max").optDouble(0, f.temp));
        f.low = (int) Math.round(d.getJSONArray("temperature_2m_min").optDouble(0, f.temp));
        JSONArray max = d.getJSONArray("temperature_2m_max");
        if (max.length() > 1) {
            f.tomorrowHigh = (int) Math.round(max.optDouble(1, f.high));
            f.tomorrowCode = d.getJSONArray("weather_code").optInt(1, -1);
        }
        f.dusk = clock(d.getJSONArray("sunset").optString(0, ""));
        JSONArray rise = d.getJSONArray("sunrise");
        int sunrise = minutes(clock(rise.optString(0, "")));
        int sunset = minutes(f.dusk);
        // Day and night by the clock at the place, not by the rounded answer.
        if (sunrise >= 0 && sunset > sunrise) {
            f.isDay = f.minutes >= sunrise && f.minutes < sunset;
            f.duskPassed = f.minutes >= sunset;
        }
        boolean risen = sunrise >= 0 ? f.minutes >= sunrise : f.hour >= 12;
        f.dawn = clock(rise.optString(risen && rise.length() > 1 ? 1 : 0, ""));
        return f;
    }

    /** "18:52" as minutes after midnight, or -1. */
    private static int minutes(String hhmm) {
        if (hhmm.length() != 5) {
            return -1;
        }
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String clock(String iso) {
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : "";
    }

    /** Places with this name, with their region and country so namesakes can be told apart. */
    static List<Place> search(Context c, String name) throws Exception {
        String url = asset(c, "talk/places-url.txt").replace("%s", URLEncoder.encode(name.trim(), "UTF-8"));
        JSONObject o = new JSONObject(get(url));
        List<Place> out = new ArrayList<Place>();
        JSONArray all = o.optJSONArray("results");
        if (all == null) {
            return out;
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            StringBuilder region = new StringBuilder();
            String admin = p.optString("admin1", "");
            String country = p.optString("country", "");
            if (!admin.isEmpty()) {
                region.append(admin);
            }
            if (!country.isEmpty()) {
                if (region.length() > 0) {
                    region.append(", ");
                }
                region.append(country);
            }
            out.add(new Place(p.optString("name", ""), region.toString(),
                    p.getDouble("latitude"), p.getDouble("longitude")));
        }
        return out;
    }

    private static String get(String address) throws IOException {
        HttpURLConnection line = (HttpURLConnection) new URL(address).openConnection();
        line.setConnectTimeout(TIMEOUT_MS);
        line.setReadTimeout(TIMEOUT_MS);
        try {
            InputStream in = line.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            line.disconnect();
        }
    }

    private static String asset(Context c, String path) throws IOException {
        InputStream in = c.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8").trim();
        } finally {
            in.close();
        }
    }

    static String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
