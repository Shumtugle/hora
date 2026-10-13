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

    /** Today's weekday and date as a sentence ("Today is Tuesday, the thirteenth of October"); null if it cannot be said. */
    static String date(Resources r, java.util.Calendar now) {
        String[] days = r.getStringArray(R.array.bot_days);
        String[] months = r.getStringArray(R.array.months_genitive);
        String[] week = r.getStringArray(R.array.bot_weekdays);
        int d = now.get(java.util.Calendar.DAY_OF_MONTH);
        int m = now.get(java.util.Calendar.MONTH);
        int w = now.get(java.util.Calendar.DAY_OF_WEEK) - 1;
        if (days.length < d || months.length <= m || week.length <= w) {
            return null;
        }
        return r.getString(R.string.bot_date, week[w], days[d - 1] + " " + months[m]);
    }

    /** The date and then the time, as said for a double press of the headphone button. */
    static String dateAndTime(Resources r) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        String time = build(r, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE));
        String date = date(r, now);
        return date == null ? time : date + " " + time;
    }

    /** Hours and minutes alone, for a sentence of its own making. */
    static String bare(Resources r, int hour, int minute) {
        String[] masculine = r.getStringArray(R.array.numbers_masculine);
        String[] feminine = r.getStringArray(R.array.numbers_feminine);
        String h = r.getQuantityString(R.plurals.hours, hour, masculine[hour]);
        String m = minute == 0
                ? r.getString(R.string.minutes_zero)
                : r.getQuantityString(R.plurals.minutes, minute, feminine[minute]);
        return h + " " + m;
    }
}
