package com.shumtugle.hora;

import android.content.res.Resources;

/**
 * Builds the spoken time. Words and plural forms come from resources of the
 * voice language, so the grammar lives in data, not in code.
 */
final class TimePhrase {
    private TimePhrase() {
    }

    static String build(Resources r, int hour, int minute) {
        String[] masculine = r.getStringArray(R.array.numbers_masculine);
        String[] feminine = r.getStringArray(R.array.numbers_feminine);
        String h = r.getQuantityString(R.plurals.hours, hour, masculine[hour]);
        String m = minute == 0
                ? r.getString(R.string.minutes_zero)
                : r.getQuantityString(R.plurals.minutes, minute, feminine[minute]);
        return r.getString(R.string.time_phrase, h, m);
    }
}
