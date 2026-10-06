package com.shumtugle.hora;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/** The language of the bundled voice. Spoken text follows it, not the UI locale. */
final class SpeechLanguage {
    static final String CODE = "ru";
    static final String ISO3 = "rus";
    static final String COUNTRY3 = "RUS";

    private SpeechLanguage() {
    }

    static final String COUNTRY = "RU";
    // Windows language id, still used by some callers as a language name.
    private static final String WINDOWS_ID = "1049";

    /** Accepts every common spelling: two- and three-letter codes, tags like xx-YY, numeric ids. */
    static boolean isLanguage(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        int cut = indexOfSeparator(n);
        String head = cut < 0 ? n : n.substring(0, cut);
        return CODE.equalsIgnoreCase(head) || ISO3.equalsIgnoreCase(head)
                || WINDOWS_ID.equals(head);
    }

    static boolean isCountry(String name) {
        return name != null && (COUNTRY.equalsIgnoreCase(name.trim())
                || COUNTRY3.equalsIgnoreCase(name.trim()));
    }

    private static int indexOfSeparator(String s) {
        int dash = s.indexOf('-');
        int under = s.indexOf('_');
        if (dash < 0) {
            return under;
        }
        return under < 0 ? dash : Math.min(dash, under);
    }

    static Locale locale() {
        return new Locale(CODE);
    }

    static Resources resources(Context context) {
        Configuration c = new Configuration(context.getResources().getConfiguration());
        c.setLocale(locale());
        return context.createConfigurationContext(c).getResources();
    }
}
