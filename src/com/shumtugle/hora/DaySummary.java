package com.shumtugle.hora;

import android.content.Context;
import android.content.res.Resources;

import java.util.List;

/**
 * The day in a few sentences, as the talking voice says it on a touch: the date and time, the sky,
 * the book and where it stands, the paper's first headline, and how much was read into one's
 * headphones today. Only what is there is said. Built off the main thread: it reads files.
 */
final class DaySummary {
    private DaySummary() {
    }

    /** The words, with an English headline marked whole for the English voice. */
    static String build(Context c) {
        Resources r = SpeechLanguage.resources(c);
        StringBuilder b = new StringBuilder();
        b.append(sentence(TimePhrase.dateAndTime(r)));
        if (Weather.hasPlace(c)) {
            // The sky the plate shows, without asking the network: a touch is answered at once.
            String sky = Weather.morningKept(c, SKY_MS);
            if (sky != null && !sky.isEmpty()) {
                b.append(' ').append(sky);
            }
        }
        String uri = Reading.uri(c);
        Library.Entry e = uri.isEmpty() ? null : Library.get(c, uri);
        if (e != null) {
            String title = Bot.bookTitle(e);
            int ch = e.chapterAt(e.index);
            // "3" or "IV" alone is said as a chapter; a label's own final dot is not doubled.
            String chapter = ch < 0 ? "" : Bot.chapterName(c, e.chapters.get(ch).label).trim();
            while (chapter.endsWith(".")) {
                chapter = chapter.substring(0, chapter.length() - 1).trim();
            }
            if (!title.isEmpty()) {
                b.append(' ').append(chapter.isEmpty() ? r.getString(R.string.day_say_book, title)
                        : r.getString(R.string.day_say_book_at, title, chapter));
            }
        }
        if (Beta.open(c) && News.on(c)) {
            News.Paper p = News.paper(c);
            if (p != null && !p.items.isEmpty()) {
                News.Item first = p.items.get(0);
                // Ended as a sentence before it is marked, so what follows is heard as the next one.
                String t = sentence(first.title);
                String head = "en".equals(first.lang) ? Foreign.whole(t) : t;
                b.append(' ').append(r.getQuantityString(R.plurals.day_say_paper, p.items.size(), p.items.size(), head));
            }
        }
        if (Heard.on(c)) {
            List<Heard.Entry> today = Heard.today(c);
            if (!today.isEmpty()) {
                b.append(' ').append(r.getString(R.string.day_say_heard, today.size()));
            }
        }
        return b.toString();
    }

    private static String sentence(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        char end = t.charAt(t.length() - 1);
        return end == '.' || end == '!' || end == '?' || end == '\u2026' ? t : t + ".";
    }

    /** How old the kept sky may be and still be said: as long as the plate shows it. */
    private static final long SKY_MS = 3 * 60 * 60 * 1000L;
}
