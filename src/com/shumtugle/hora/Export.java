package com.shumtugle.hora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Books made into audiobooks while the phone charges. A book waits in a queue;
 * the system wakes Hora when the phone is on the charger, Hora works for a
 * few minutes, keeps what it made and asks to be woken again. Each chapter's
 * sound is gathered paragraph by paragraph into a raw file, so stopping loses
 * at most one paragraph; a finished chapter is packed into a small file and
 * put in the book's folder as 1, 2, 3..., with the chapter names in a list
 * beside them. Nothing runs off the charger, and nothing while a book plays.
 */
final class Export {
    private static final int JOB = 7301;
    private static final String CHANNEL = "export";
    private static final long SLICE_MS = 8 * 60 * 1000L;
    /** How long to wait before looking again when a book is playing or a slice failed. */
    static final long LATER_MS = 5 * 60 * 1000L;
    /** A book that failed this many times in a row stays in the queue but is no longer tried. */
    private static final int TRIES = 3;
    /** Work counts as going on when the last paragraph was kept this recently. */
    static final long FRESH_MS = 3 * 60 * 1000L;
    /**
     * Chapters are written as two equal channels: on some phones the platform player plays
     * a long single-channel file as silence while its clock runs, and two channels cost
     * little more.
     */
    private static final int BITRATE = 64000;
    private static final int CHANNELS = 2;
    private static final String STATE = "export.json";

    private Export() {
    }

    // The queue.

    static synchronized JSONArray queue(Context c) {
        File f = new File(c.getFilesDir(), STATE);
        if (!f.isFile()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(new String(LanguagePack.read(f), "UTF-8"));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static synchronized void keep(Context c, JSONArray q) {
        File f = new File(c.getFilesDir(), STATE);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(q.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            Diag.log(c, "export: queue not kept", e);
            return;
        }
        tmp.renameTo(f);
    }

    static boolean queued(Context c, String uri) {
        JSONArray q = queue(c);
        for (int i = 0; i < q.length(); i++) {
            if (uri.equals(q.optJSONObject(i).optString("uri"))) {
                return true;
            }
        }
        return false;
    }

    /** Puts a book in the queue, read by the given voice, and asks to be woken on the charger. */
    static void add(Context c, String uri, String name, String title, int reader, int chapters) {
        JSONArray q = queue(c);
        try {
            JSONObject j = new JSONObject();
            j.put("uri", uri).put("name", name).put("title", title).put("reader", reader)
                    .put("chapters", chapters).put("chapter", 0).put("paragraph", -1).put("added", System.currentTimeMillis());
            q.put(j);
        } catch (JSONException e) {
            return;
        }
        keep(c, q);
        Diag.mark(c, "export: queued, " + chapters + " chapters");
        schedule(c);
    }

    static void cancel(Context c, String uri) {
        JSONArray q = queue(c);
        JSONArray rest = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject j = q.optJSONObject(i);
            if (uri.equals(j.optString("uri"))) {
                pcm(c).delete();
            } else {
                rest.put(j);
            }
        }
        keep(c, rest);
    }

    /**
     * Asks the system to wake Hora for this work when the phone is charging, unless that is
     * already asked for or running: asking again would stop the running work and start a
     * second worker beside the first.
     */
    static void schedule(Context c) {
        if (queue(c).length() == 0) {
            return;
        }
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js.getPendingJob(JOB) != null) {
            return;
        }
        schedule(c, 0);
    }

    /** Asks for the next wake-up from the job itself, after the given delay. */
    static void schedule(Context c, long delayMs) {
        if (queue(c).length() == 0) {
            return;
        }
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder b = new JobInfo.Builder(JOB, new ComponentName(c, ExportJob.class)).setRequiresCharging(true);
        if (delayMs > 0) {
            b.setMinimumLatency(delayMs);
        }
        js.schedule(b.build());
    }

    /** Gives a book that stopped trying another chance. */
    static void retry(Context c, String uri) {
        JSONArray q = queue(c);
        for (int i = 0; i < q.length(); i++) {
            JSONObject j = q.optJSONObject(i);
            if (uri.equals(j.optString("uri"))) {
                j.remove("failed");
                j.remove("failures");
            }
        }
        keep(c, q);
        Diag.mark(c, "export: tried again");
        schedule(c);
    }

    // Where the files go.

    /** The folder audiobooks go to: one the user showed for them, or Hora's own. Null until either is known. */
    static Uri target(Context c) throws IOException {
        Uri own = ownTree(c);
        if (own != null) {
            return DocumentsContract.buildDocumentUriUsingTree(own, DocumentsContract.getTreeDocumentId(own));
        }
        if (HoraFolder.tree(c) == null) {
            return null;
        }
        return HoraFolder.sub(c, c.getString(R.string.folder_audiobooks));
    }

    /** The folder shown for audiobooks, if Hora may still write there. */
    static Uri ownTree(Context c) {
        String s = c.getSharedPreferences("hora", Context.MODE_PRIVATE).getString("audiobooks_folder", null);
        if (s == null) {
            return null;
        }
        Uri u = Uri.parse(s);
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) {
                return u;
            }
        }
        return null;
    }

    static boolean acceptFolder(Context c, Intent data) {
        if (data == null || data.getData() == null) {
            return false;
        }
        try {
            c.getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            return false;
        }
        c.getSharedPreferences("hora", Context.MODE_PRIVATE).edit()
                .putString("audiobooks_folder", data.getData().toString()).apply();
        return true;
    }

    /** A named folder inside another, made when missing; works in any shown folder. */
    private static Uri dirIn(Context c, Uri parent, String name) throws IOException {
        Uri found = find(c, parent, name);
        if (found != null) {
            return found;
        }
        try {
            Uri made = DocumentsContract.createDocument(c.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (made == null) {
                throw new IOException("folder not made");
            }
            return made;
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static Uri find(Context c, Uri parent, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent));
        try (android.database.Cursor cur = c.getContentResolver().query(kids, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (name.equals(cur.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cur.getString(0));
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static void put(Context c, Uri dir, String name, String mime, File from) throws IOException {
        Uri file = find(c, dir, name);
        try {
            if (file == null) {
                file = DocumentsContract.createDocument(c.getContentResolver(), dir, mime, name);
            }
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
        if (file == null) {
            throw new IOException("file not made");
        }
        try (InputStream in = new FileInputStream(from); OutputStream out = c.getContentResolver().openOutputStream(file, "wt")) {
            if (out == null) {
                throw new IOException("cannot write");
            }
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }

    // The work.

    private static File pcm(Context c) {
        return new File(c.getFilesDir(), "export-chapter.pcm");
    }

    /** The parts of a book: paragraph ranges per chapter, with names; one part when it has no chapters. */
    static List<int[]> parts(Book b) {
        List<int[]> out = new ArrayList<int[]>();
        int n = b.paragraphs.size();
        if (b.chapters.isEmpty()) {
            out.add(new int[] {0, n});
            return out;
        }
        for (int i = 0; i < b.chapters.size(); i++) {
            int from = i == 0 ? 0 : b.chapters.get(i).at;
            int to = i + 1 < b.chapters.size() ? b.chapters.get(i + 1).at : n;
            out.add(new int[] {from, to});
        }
        return out;
    }

    static Book load(Context c, String uri, String name) throws IOException {
        android.content.res.Resources res = SpeechLanguage.resources(c);
        try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
            return BookText.read(name, in, res.getString(R.string.chapter_words), res.getString(R.string.chapter_ordinals));
        }
    }

    /** What a slice of work asks for next. */
    static final long NOTHING_LEFT = -1;

    /**
     * One slice of work: as much of the queue as fits in a few minutes. Returns when to come
     * back: 0 at once, a delay when a book is playing or the work failed, or NOTHING_LEFT.
     * Runs in the voice process, on the job's own thread, one worker at a time.
     */
    static long work(Context c, Voice.Cancel stop) {
        long until = SystemClock.elapsedRealtime() + SLICE_MS;
        while (SystemClock.elapsedRealtime() < until && !stop.cancelled()) {
            if (Reading.playing(c)) {
                Diag.mark(c, "export: a book is playing, later");
                return LATER_MS;
            }
            JSONArray q = queue(c);
            int at = next(q);
            if (at < 0) {
                return NOTHING_LEFT;
            }
            JSONObject job = q.optJSONObject(at);
            String uri = job.optString("uri");
            Diag.mark(c, "export: working, chapter " + (job.optInt("chapter") + 1) + " of " + job.optInt("chapters")
                    + ", paragraph " + Math.max(0, job.optInt("paragraph")));
            try {
                boolean done = step(c, job, until, stop);
                // The job object carries its own progress; keep it whatever happened.
                q = queue(c);
                int now = find(q, uri);
                if (now >= 0) {
                    if (done) {
                        q.remove(now);
                        finished(c, job);
                    } else {
                        q.put(now, job);
                    }
                    keep(c, q);
                }
            } catch (Exception e) {
                // The book stays in the queue with its place; a few failures in a row set it aside.
                q = queue(c);
                int now = find(q, uri);
                int failures = 1;
                if (now >= 0) {
                    JSONObject j = q.optJSONObject(now);
                    failures = j.optInt("failures") + 1;
                    try {
                        j.put("failures", failures);
                        if (failures >= TRIES) {
                            j.put("failed", true);
                        }
                    } catch (JSONException ignored) {
                        // The count is a convenience; the place is what matters.
                    }
                    keep(c, q);
                }
                Diag.log(c, "export: a slice failed, " + failures + " of " + TRIES, e);
                return LATER_MS;
            }
        }
        return next(queue(c)) >= 0 ? 0 : NOTHING_LEFT;
    }

    /** The first book still to be tried, or -1. */
    private static int next(JSONArray q) {
        for (int i = 0; i < q.length(); i++) {
            if (!q.optJSONObject(i).optBoolean("failed")) {
                return i;
            }
        }
        return -1;
    }

    private static int find(JSONArray q, String uri) {
        for (int i = 0; i < q.length(); i++) {
            if (uri.equals(q.optJSONObject(i).optString("uri"))) {
                return i;
            }
        }
        return -1;
    }

    /** Works on one book until the time is up or it is finished; true when finished. */
    private static boolean step(Context c, JSONObject job, long until, Voice.Cancel stop) throws Exception {
        String uri = job.getString("uri");
        Book b = load(c, uri, job.getString("name"));
        List<int[]> parts = parts(b);
        Voice voice = Voice.get(c);
        voice.prepare();
        int rate = voice.sampleRate();
        int reader = job.optInt("reader", 1);
        while (job.getInt("chapter") < parts.size()) {
            int ch = job.getInt("chapter");
            int[] range = parts.get(ch);
            int p = job.getInt("paragraph");
            if (p >= range[0] && pcm(c).length() < job.optLong("bytes", 0)) {
                // The sound kept so far is shorter than the place says: the chapter starts over.
                Diag.mark(c, "export: chapter " + (ch + 1) + " started over, its sound was short");
                p = -1;
                job.put("bytes", 0);
            }
            if (p < range[0]) {
                // A chapter begins: a fresh sound file.
                pcm(c).delete();
                p = range[0];
                job.put("paragraph", p);
            }
            job.put("from", range[0]).put("to", range[1]);
            try (RandomAccessFile out = new RandomAccessFile(pcm(c), "rw")) {
                out.seek(job.optLong("bytes", 0));
                while (p < range[1]) {
                    if (stop.cancelled() || SystemClock.elapsedRealtime() > until || Reading.playing(c)) {
                        return false;
                    }
                    String spoken = voice.normalize(Lexicon.get(c).applyRules(TextPrep.clean(b.paragraphs.get(p))));
                    float[] s = voice.render(spoken, SpeechLanguage.locale(), Prefs.speedShared(c),
                            Prefs.pauseMsShared(c), stop, null, false, reader);
                    if (stop.cancelled()) {
                        return false;
                    }
                    out.write(pcm16(s));
                    p++;
                    job.put("paragraph", p);
                    job.put("bytes", out.getFilePointer());
                    job.put("at", System.currentTimeMillis());
                    // Progress kept after every paragraph: a killed process loses at most one.
                    progress(c, job);
                }
            }
            pack(c, job, b, ch, rate);
            job.remove("failures");
            job.put("chapter", ch + 1);
            job.put("paragraph", -1);
            job.put("bytes", 0);
        }
        return true;
    }

    private static void progress(Context c, JSONObject job) {
        JSONArray q = queue(c);
        int at = find(q, job.optString("uri"));
        if (at >= 0) {
            try {
                q.put(at, job);
            } catch (JSONException e) {
                return;
            }
            keep(c, q);
        }
    }

    private static byte[] pcm16(float[] s) {
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, s[i])) * 32767f);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** A finished chapter: packed into a small file and put in the book's folder. */
    private static void pack(Context c, JSONObject job, Book b, int chapter, int rate) throws Exception {
        File m4a = new File(c.getCacheDir(), "export-chapter.m4a");
        encode(pcm(c), m4a, rate);
        String title = job.getString("title");
        String label = b.chapters.isEmpty() || chapter >= b.chapters.size() ? title : b.chapters.get(chapter).label;
        try {
            tag(m4a, title, label, chapter + 1, job.optInt("chapters"), b.author, cover(c, job, title, b.author));
        } catch (IOException e) {
            Diag.log(c, "export: chapter not tagged", e);
        }
        checkLength(c, m4a, rate, chapter + 1);
        Uri root = target(c);
        if (root == null) {
            throw new IOException("no folder for audiobooks");
        }
        Uri dir = dirIn(c, root, safe(job.getString("title")));
        put(c, dir, (chapter + 1) + ".m4a", "audio/mp4", m4a);
        m4a.delete();
        pcm(c).delete();
        Diag.mark(c, "export: chapter " + (chapter + 1) + " of " + job.optInt("chapters") + " made");
    }

    /**
     * Opens the packed chapter the way a player would and compares its length
     * with the sound that went in. A short file means the packing broke off;
     * the sound is still kept, so the next try packs it again.
     */
    private static void checkLength(Context c, File m4a, int rate, int number) throws IOException {
        double want = pcm(c).length() / 2.0 / rate;
        android.media.MediaExtractor x = new android.media.MediaExtractor();
        double got;
        try {
            x.setDataSource(m4a.getPath());
            if (x.getTrackCount() < 1) {
                throw new IOException("packed chapter has no track");
            }
            android.media.MediaFormat f = x.getTrackFormat(0);
            got = f.containsKey(android.media.MediaFormat.KEY_DURATION)
                    ? f.getLong(android.media.MediaFormat.KEY_DURATION) / 1e6 : 0;
        } finally {
            x.release();
        }
        double off = Math.abs(got - want);
        String line = String.format(java.util.Locale.ROOT, "export: chapter %d packed %.1f s of %.1f s", number, got, want);
        if (off > Math.max(1.0, want * 0.02)) {
            Diag.mark(c, line + ", packing again");
            throw new IOException("packed chapter " + number + " is " + got + " s, sound " + want + " s");
        }
        Diag.log(c, line);
    }

    private static String coverFor = "";
    private static byte[] coverBytes;

    /** The book's front picture, found once per book and kept while its chapters are made. */
    private static synchronized byte[] cover(Context c, JSONObject job, String title, String author) {
        String uri = job.optString("uri");
        if (!uri.equals(coverFor)) {
            coverFor = uri;
            coverBytes = BookCover.jpeg(c, uri, job.optString("name"), title, author);
        }
        return coverBytes;
    }

    private static String safe(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "Hora" : t;
    }

    /** Reads up to n bytes, always a whole number of 16-bit samples; 0 at the end. */
    private static int readEven(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r <= 0) {
                break;
            }
            got += r;
            if ((got & 1) == 0) {
                break;
            }
        }
        return got & ~1;
    }

    /** Raw 16-bit single-channel sound into a two-channel AAC file, with the platform's own encoder. */
    static void encode(File pcm, File out, int rate) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, CHANNELS);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        int track = -1;
        boolean inputDone = false;
        boolean outputDone = false;
        long samples = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try (InputStream in = new FileInputStream(pcm)) {
            byte[] chunk = new byte[8192];
            byte[] both = new byte[chunk.length * 2];
            while (!outputDone) {
                if (!inputDone) {
                    int i = codec.dequeueInputBuffer(10_000);
                    if (i >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(i);
                        buf.clear();
                        int r = readEven(in, chunk, Math.min(chunk.length, buf.remaining() / 2) & ~1);
                        long pts = samples * 1_000_000L / rate;
                        if (r <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            for (int k = 0; k < r; k += 2) {
                                both[2 * k] = chunk[k];
                                both[2 * k + 1] = chunk[k + 1];
                                both[2 * k + 2] = chunk[k];
                                both[2 * k + 3] = chunk[k + 1];
                            }
                            buf.put(both, 0, 2 * r);
                            codec.queueInputBuffer(i, 0, 2 * r, pts, 0);
                            samples += r / 2;
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat());
                    mux.start();
                } else if (o >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && track >= 0) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        mux.writeSampleData(track, data, info);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            codec.stop();
            codec.release();
            if (track >= 0) {
                mux.stop();
            }
            mux.release();
        }
    }

    /** Every chapter says, in a form machines read, that its voice is synthetic. */
    static final String SYNTHETIC = "Synthetic speech: the voice in this recording was generated by Hora, not recorded from a person.";

    /**
     * Adds the book, the chapter and the synthetic-speech notice to a finished file.
     * The platform's muxer puts the movie box last, so a user-data box is appended to it.
     */
    static void tag(File m4a, String book, String chapter, int number, int of) throws IOException {
        tag(m4a, book, chapter, number, of, null, null);
    }

    /** The same, with the author as the artist and a cover picture when there are such. */
    static void tag(File m4a, String book, String chapter, int number, int of, String author, byte[] cover)
            throws IOException {
        byte[] d = java.nio.file.Files.readAllBytes(m4a.toPath());
        int at = 0;
        int moov = -1;
        while (at + 8 <= d.length) {
            long size = ByteBuffer.wrap(d, at, 4).getInt() & 0xffffffffL;
            String type = new String(d, at + 4, 4, "ISO-8859-1");
            if (size == 1 && at + 16 <= d.length) {
                size = ByteBuffer.wrap(d, at + 8, 8).getLong();
            } else if (size == 0) {
                size = d.length - at;
            }
            if (size < 8 || at + size > d.length) {
                throw new IOException("box out of bounds");
            }
            if (type.equals("moov")) {
                moov = at;
            }
            at += (int) size;
        }
        int moovSize = moov < 0 ? 0 : ByteBuffer.wrap(d, moov, 4).getInt();
        if (moov < 0 || moovSize < 8 || moov + moovSize != d.length) {
            throw new IOException("movie box not last");
        }
        java.io.ByteArrayOutputStream ilst = new java.io.ByteArrayOutputStream();
        ilst.write(item("\u00a9alb", text(book)));
        ilst.write(item("\u00a9nam", text(chapter)));
        ilst.write(item("\u00a9cmt", text(SYNTHETIC)));
        ilst.write(item("\u00a9too", text("Hora")));
        if (author != null && !author.trim().isEmpty()) {
            ilst.write(item("\u00a9ART", text(author.trim())));
            ilst.write(item("aART", text(author.trim())));
        }
        if (cover != null && cover.length > 0) {
            // Type 13 marks a JPEG picture.
            ByteBuffer pic = ByteBuffer.allocate(16 + cover.length);
            pic.putInt(16 + cover.length).put("data".getBytes("ISO-8859-1")).putInt(13).putInt(0).put(cover);
            ilst.write(box("covr", pic.array()));
        }
        ByteBuffer track = ByteBuffer.allocate(16 + 8);
        track.putInt(16 + 8).put("data".getBytes("ISO-8859-1")).putInt(0).putInt(0)
                .putShort((short) 0).putShort((short) number).putShort((short) of).putShort((short) 0);
        ilst.write(box("trkn", track.array()));
        ByteBuffer hdlr = ByteBuffer.allocate(25);
        hdlr.putInt(0).putInt(0).put("mdir".getBytes("ISO-8859-1")).put("appl".getBytes("ISO-8859-1"))
                .putInt(0).putInt(0).put((byte) 0);
        java.io.ByteArrayOutputStream meta = new java.io.ByteArrayOutputStream();
        meta.write(new byte[4]);
        meta.write(box("hdlr", hdlr.array()));
        meta.write(box("ilst", ilst.toByteArray()));
        byte[] udta = box("udta", box("meta", meta.toByteArray()));
        ByteBuffer.wrap(d, moov, 4).putInt(moovSize + udta.length);
        try (OutputStream out = new FileOutputStream(m4a)) {
            out.write(d);
            out.write(udta);
        }
    }

    private static byte[] text(String s) throws IOException {
        byte[] t = s.getBytes("UTF-8");
        ByteBuffer b = ByteBuffer.allocate(16 + t.length);
        b.putInt(16 + t.length).put("data".getBytes("ISO-8859-1")).putInt(1).putInt(0).put(t);
        return b.array();
    }

    private static byte[] item(String name, byte[] data) throws IOException {
        return box(name, data);
    }

    private static byte[] box(String type, byte[] body) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(8 + body.length).put(type.getBytes("ISO-8859-1")).put(body);
        return b.array();
    }

    /** A whole book done: the chapter names beside the files, and a word to the user. */
    private static void finished(Context c, JSONObject job) {
        try {
            Book b = load(c, job.getString("uri"), job.getString("name"));
            StringBuilder list = new StringBuilder();
            List<int[]> parts = parts(b);
            for (int i = 0; i < parts.size(); i++) {
                String label = b.chapters.isEmpty() ? job.getString("title") : b.chapters.get(i).label;
                list.append(i + 1).append(' ').append(label).append('\n');
            }
            list.append('\n').append(c.getString(R.string.export_synthetic_note)).append('\n');
            File txt = new File(c.getCacheDir(), "chapters.txt");
            try (OutputStream out = new FileOutputStream(txt)) {
                out.write(list.toString().getBytes("UTF-8"));
            }
            Uri dir = dirIn(c, target(c), safe(job.getString("title")));
            put(c, dir, c.getString(R.string.export_chapters_file), "text/plain", txt);
            txt.delete();
        } catch (Exception e) {
            Diag.log(c, "export: chapter list not written", e);
        }
        Diag.mark(c, "export: a book finished, " + job.optInt("chapters") + " chapters");
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.export_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, ExportActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        nm.notify(JOB + job.optString("uri").hashCode() % 1000, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_book)
                .setContentTitle(c.getString(R.string.export_done_title, job.optString("title")))
                .setContentText(c.getResources().getQuantityString(R.plurals.export_done_text,
                        job.optInt("chapters"), job.optInt("chapters")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    /** Rough length of the sound and of the work, in minutes, from the letters of the text. */
    static int[] estimate(Book b) {
        long letters = 0;
        for (String p : b.paragraphs) {
            letters += p.length();
        }
        int soundMin = (int) (letters / 14 / 60);
        return new int[] {Math.max(1, soundMin), Math.max(1, (int) (soundMin / 1.9f))};
    }
}
