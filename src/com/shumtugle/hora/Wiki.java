package com.shumtugle.hora;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * The reference: a question about the world goes to the free encyclopedia,
 * not to the small model's memory. The question is cut down to its subject,
 * the best-matching article is found, and its text comes back: the opening
 * for reading aloud as it is, a longer stretch for the model to answer from.
 *
 * The addresses and the name the app gives itself live in the bundle.
 */
final class Wiki {
    /** How much of an article the model reads: enough for a date deep in a history section. */
    private static final int LONG = 6000;

    static final class Article {
        String title;
        String link;
        /** The first sentences, plain, for reading aloud without the model. */
        String opening;
        /** A longer stretch of plain text for the model to answer from. */
        String body;
    }

    private Wiki() {
    }

    /** Which edition to ask: a question in Latin letters goes to the English one. */
    static String edition(String question) {
        int latin = 0;
        int cyrillic = 0;
        for (char ch : question.toCharArray()) {
            if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') {
                latin++;
            } else if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC) {
                cyrillic++;
            }
        }
        return latin > cyrillic ? "en" : "ru";
    }

    /** The question without its question words, leaving what it is about. */
    static String subject(Resources r, String question) {
        String t = " " + Intents.fold(question).replaceAll("[^\\p{L}\\p{N}-]+", " ") + " ";
        for (String w : r.getStringArray(R.array.bot_wiki_drop)) {
            String f = Intents.fold(w).trim();
            if (!f.isEmpty()) {
                t = t.replace(" " + f + " ", " ");
                t = t.replace(" " + f + " ", " ");
            }
        }
        return t.trim().replaceAll("\\s+", " ");
    }

    /** Finds and reads the article; network, never on the main thread. Null when nothing fits. */
    static Article look(Context c, String question) throws IOException {
        Resources r = SpeechLanguage.resources(c);
        String what = subject(r, question);
        if (what.isEmpty()) {
            return null;
        }
        String lang = edition(question);
        String api = String.format(asset(c, "talk/wiki-url.txt"), lang);
        JSONObject found = get(c, api + "?action=query&format=json&formatversion=2&redirects=1"
                + "&generator=search&gsrlimit=1&gsrsearch=" + URLEncoder.encode(what, "UTF-8")
                + "&prop=extracts|info&inprop=url&explaintext=1&exsectionformat=plain");
        try {
            JSONArray pages = found.optJSONObject("query") == null ? null
                    : found.getJSONObject("query").optJSONArray("pages");
            if (pages == null || pages.length() == 0) {
                return null;
            }
            JSONObject p = pages.getJSONObject(0);
            Article a = new Article();
            a.title = p.optString("title");
            a.link = p.optString("fullurl");
            String text = p.optString("extract").trim();
            if (text.isEmpty()) {
                return null;
            }
            a.body = text.length() > LONG ? text.substring(0, LONG) : text;
            a.opening = opening(text);
            return a;
        } catch (org.json.JSONException e) {
            throw new IOException("reference: " + e.getMessage());
        }
    }

    /** The first one or two sentences, without the bracketed asides (pronunciation, dates of life). */
    static String opening(String text) {
        String first = text.split("\n", 2)[0];
        String plain = first.replaceAll("\\s*\\([^()]*\\)", "").replaceAll("\\s+", " ").trim();
        int end = -1;
        int sentences = 0;
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            if ((ch == '.' || ch == '!' || ch == '?') && (i + 1 == plain.length() || plain.charAt(i + 1) == ' ')) {
                // An initial ("J. R.") is not the end of a sentence.
                if (i >= 2 && plain.charAt(i - 2) == ' ' && Character.isUpperCase(plain.charAt(i - 1))) {
                    continue;
                }
                end = i + 1;
                if (++sentences == 2 || end > 220) {
                    break;
                }
            }
        }
        return end > 0 ? plain.substring(0, end) : plain;
    }

    private static JSONObject get(Context c, String address) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        // The encyclopedia asks every program to say who it is.
        h.setRequestProperty("User-Agent", asset(c, "talk/wiki-agent.txt"));
        try {
            if (h.getResponseCode() != 200) {
                throw new IOException("reference: http " + h.getResponseCode());
            }
            return new JSONObject(new String(LanguagePack.readAll(h.getInputStream()), "UTF-8"));
        } catch (org.json.JSONException e) {
            throw new IOException("reference: " + e.getMessage());
        } finally {
            h.disconnect();
        }
    }

    private static String asset(Context c, String name) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(name), "UTF-8"))) {
            return r.readLine().trim();
        }
    }
}
