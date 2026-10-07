package com.shumtugle.hora;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * A small language model on the phone, for talk that is not a command. The
 * model runs as a separate local server process started from the app's own
 * native library folder; Hora talks to it over the phone's loopback address,
 * so nothing leaves the device. It wakes on demand and sleeps after a quiet
 * spell, giving its memory back to the voice.
 */
final class Brain {
    private static final String TAG = "brain";
    private static final String DIR = "brain";
    private static final String MODEL = "model.gguf";
    /** The three places for files: the model, its eyes, and its fast guesser. */
    static final int SLOT_MODEL = 0;
    static final int SLOT_VISION = 1;
    static final int SLOT_DRAFT = 2;
    private static final String[] SLOT_FILES = {MODEL, "vision.gguf", "draft.gguf"};
    private static final String LOG = "server.log";
    private static final String PROGRAM = "libhora_brain.so";
    private static final int PORT = 18521;
    private static final int CONTEXT = 2048;
    private static final int THREADS = 4;
    private static final long WAKE_LIMIT_MS = 120_000;
    private static final long SLEEP_AFTER_MS = 5 * 60_000;

    /** A reply and how it was made. */
    static final class Reply {
        final String text;
        final double seconds;
        final double tokensPerSecond;

        Reply(String text, double seconds, double tokensPerSecond) {
            this.text = text;
            this.seconds = seconds;
            this.tokensPerSecond = tokensPerSecond;
        }
    }

    private static Brain instance;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Process process;
    private double wokeInSeconds;
    private double lastSpeed;
    /** Set when the server would not start with the guesser and runs without it. */
    private boolean draftRefused;
    /** Set when the engine would not start with the photo part: it belongs to another model. */
    private boolean visionRefused;

    private Brain(Context c) {
        context = c.getApplicationContext();
    }

    /**
     * Puts the model to sleep when Hora has gone to the background, if it is awake: the
     * system would end the whole app for its memory within a minute or two anyway, and the
     * voice process with it. Runs off the main thread; a wake in progress finishes first.
     */
    static void sleepInBackground() {
        final Brain b;
        synchronized (Brain.class) {
            b = instance;
        }
        if (b == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (b) {
                    if (b.process != null) {
                        Diag.mark(b.context, TAG + ": Hora in the background");
                        b.stopNow();
                    }
                }
            }
        }, "brain-sleep").start();
    }

    static synchronized Brain get(Context c) {
        if (instance == null) {
            instance = new Brain(c);
        }
        return instance;
    }

    File folder() {
        File f = new File(context.getFilesDir(), DIR);
        if (!f.isDirectory()) {
            f.mkdirs();
        }
        return f;
    }

    /** Where downloaded files land: the app's own outer storage, so a big file is not copied twice. */
    File outerFolder() {
        File f = context.getExternalFilesDir(DIR);
        if (f == null) {
            f = folder();
        }
        f.mkdirs();
        return f;
    }

    File modelFile() {
        return slotFile(SLOT_MODEL);
    }

    /** A place's file: a picked copy inside the app, or else a downloaded one outside. */
    File slotFile(int slot) {
        File inner = new File(folder(), SLOT_FILES[slot]);
        if (inner.isFile()) {
            return inner;
        }
        File outer = new File(outerFolder(), SLOT_FILES[slot]);
        return outer.isFile() ? outer : inner;
    }

    /** Puts a downloaded, checked file in a place, replacing whatever was there. */
    void adopt(int slot, File downloaded, String name) throws IOException {
        stop();
        new File(folder(), SLOT_FILES[slot]).delete();
        File target = new File(outerFolder(), SLOT_FILES[slot]);
        if (target.exists() && !target.delete()) {
            throw new IOException("old file could not be removed");
        }
        if (!downloaded.renameTo(target)) {
            throw new IOException("file could not be put in place");
        }
        draftRefused = false;
        visionRefused = false;
        context.getSharedPreferences("hora", Context.MODE_PRIVATE).edit()
                .putString("brain_name_" + slot, name == null ? "" : name).apply();
        Diag.mark(context, TAG + ": slot " + slot + " downloaded, " + target.length() / 1_000_000 + " MB");
    }

    boolean has(int slot) {
        return slotFile(slot).isFile() && slotFile(slot).length() > 1_000_000;
    }

    /** The name the file had when it was picked, for the screen. */
    String slotName(int slot) {
        return context.getSharedPreferences("hora", Context.MODE_PRIVATE).getString("brain_name_" + slot, "");
    }

    /** Removes the file in a place; the model sleeps first. */
    void remove(int slot) {
        stop();
        boolean outer = new File(outerFolder(), SLOT_FILES[slot]).delete();
        if (new File(folder(), SLOT_FILES[slot]).delete() || outer) {
            context.getSharedPreferences("hora", Context.MODE_PRIVATE).edit().remove("brain_name_" + slot).apply();
            Diag.mark(context, TAG + ": removed slot " + slot);
        }
    }

    /** Bytes taken by all the files. */
    long size() {
        long n = 0;
        for (int i = 0; i < SLOT_FILES.length; i++) {
            n += new File(folder(), SLOT_FILES[i]).length() + new File(outerFolder(), SLOT_FILES[i]).length();
        }
        return n;
    }

    boolean awake() {
        return alive();
    }

    /** Tokens per second of the last reply; 0 if none yet. */
    double lastSpeed() {
        return lastSpeed;
    }

    boolean hasModel() {
        return modelFile().isFile() && modelFile().length() > 1_000_000;
    }

    /** Seconds the last wake took; 0 if the model was already awake. */
    double wokeIn() {
        return wokeInSeconds;
    }

    /** Copies a model the user picked into the app; the old one is replaced only when the copy is whole. */
    void importModel(InputStream in, Progress progress) throws IOException {
        importFile(SLOT_MODEL, in, "", progress);
    }

    /** Copies a picked file into a place; the old one there is replaced only when the copy is whole. */
    void importFile(int slot, InputStream in, String name, Progress progress) throws IOException {
        stop();
        File tmp = new File(folder(), SLOT_FILES[slot] + ".part");
        byte[] buf = new byte[1 << 20];
        long done = 0;
        try (OutputStream out = new java.io.FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) {
                    progress.copied(done);
                }
            }
        } finally {
            in.close();
        }
        // A picked file replaces a downloaded one in the same place.
        new File(outerFolder(), SLOT_FILES[slot]).delete();
        File target = new File(folder(), SLOT_FILES[slot]);
        if (target.exists() && !target.delete()) {
            throw new IOException("old file could not be removed");
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("file could not be put in place");
        }
        draftRefused = false;
        visionRefused = false;
        context.getSharedPreferences("hora", Context.MODE_PRIVATE).edit()
                .putString("brain_name_" + slot, name == null ? "" : name).apply();
        Diag.mark(context, TAG + ": slot " + slot + " imported, " + done / 1_000_000 + " MB");
    }

    interface Progress {
        void copied(long bytes);
    }

    private boolean alive() {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /** Whether the running server was started with the photo part. */
    private boolean servingVision;

    /** Starts the server for talk, without the photo part. */
    synchronized void wake() throws IOException {
        wake(false);
    }

    /**
     * Starts the server when it is not running and waits until the model has loaded. The photo
     * part, about a gigabyte, comes in only for a question with a picture; the server is
     * restarted with it then, and the next start for talk leaves it out again.
     */
    synchronized void wake(boolean photo) throws IOException {
        handler.removeCallbacksAndMessages(null);
        wokeInSeconds = 0;
        boolean seeing = photo && has(SLOT_VISION) && !visionRefused;
        if (alive() && healthy() && (servingVision || !seeing)) {
            return;
        }
        if (!hasModel()) {
            throw new IOException("no model");
        }
        stopNow();
        boolean withDraft = has(SLOT_DRAFT) && !draftRefused && draftOn(context);
        boolean withVision = seeing;
        try {
            start(withDraft, withVision);
            return;
        } catch (IOException e) {
            if (!withDraft && !withVision) {
                throw e;
            }
        }
        // Extras that do not fit the model are dropped one by one: first the guesser, then the photo part.
        if (withDraft) {
            draftRefused = true;
            Diag.mark(context, TAG + ": runs without the guesser");
            try {
                start(false, withVision);
                return;
            } catch (IOException e) {
                if (!withVision) {
                    throw e;
                }
            }
        }
        visionRefused = true;
        Diag.mark(context, TAG + ": runs without the photo part");
        start(false, false);
    }

    boolean visionRefused() {
        return visionRefused;
    }

    /** Whether the model in place is the one the app offers by default, by the name it came under. */
    boolean isDefaultModel() {
        String name = slotName(SLOT_MODEL);
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                context.getAssets().open("talk/model-default.txt"), "UTF-8"))) {
            return name.equals(r.readLine().trim());
        } catch (IOException e) {
            return true;
        }
    }

    private void start(boolean withDraft, boolean withVision) throws IOException {
        stopNow();
        String libs = context.getApplicationInfo().nativeLibraryDir;
        java.util.List<String> cmd = new java.util.ArrayList<String>(java.util.Arrays.asList(
                new File(libs, PROGRAM).getPath(),
                "-m", modelFile().getPath(), "--host", "127.0.0.1", "--port", String.valueOf(PORT),
                "-c", String.valueOf(CONTEXT), "-t", String.valueOf(THREADS), "--jinja"));
        servingVision = withVision;
        if (withVision) {
            cmd.add("--mmproj");
            cmd.add(slotFile(SLOT_VISION).getPath());
        }
        if (withDraft) {
            cmd.add("-md");
            cmd.add(slotFile(SLOT_DRAFT).getPath());
            // A drafter built into the model's family (multi-token prediction) needs its own mode;
            // it guesses a few words ahead and the model checks each, so answers stay the same.
            if (architecture(slotFile(SLOT_DRAFT)).endsWith("_mtp")) {
                cmd.add("--spec-type");
                cmd.add("draft-mtp");
                cmd.add("--spec-draft-n-max");
                cmd.add("4");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", libs);
        pb.directory(folder());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(folder(), LOG));
        long started = System.nanoTime();
        process = pb.start();
        Diag.mark(context, TAG + ": starting");
        while (System.nanoTime() - started < WAKE_LIMIT_MS * 1_000_000L) {
            if (!alive()) {
                throw new IOException("server stopped: " + tail());
            }
            if (healthy()) {
                wokeInSeconds = (System.nanoTime() - started) / 1e9;
                Diag.mark(context, String.format(java.util.Locale.ROOT, "%s: awake in %.1f s", TAG, wokeInSeconds));
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        stopNow();
        throw new IOException("server did not wake: " + tail());
    }

    /** The architecture a model file declares in its header, or "" when it cannot be read. */
    static String architecture(File f) {
        byte[] key = "general.architecture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] head = new byte[1 << 16];
        int n = 0;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) {
                n += r;
            }
        } catch (IOException e) {
            return "";
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(head, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + key.length + 12 < n; i++) {
            int k = 0;
            while (k < key.length && head[i + k] == key[k]) {
                k++;
            }
            if (k < key.length) {
                continue;
            }
            int at = i + key.length;
            // After the key: the value's type (8 is a string), then its length and bytes.
            if (b.getInt(at) != 8) {
                return "";
            }
            long len = b.getLong(at + 4);
            if (len <= 0 || len > 64 || at + 12 + len > n) {
                return "";
            }
            return new String(head, at + 12, (int) len, java.nio.charset.StandardCharsets.US_ASCII);
        }
        return "";
    }

    boolean draftRefused() {
        return draftRefused;
    }

    private boolean healthy() {
        try {
            HttpURLConnection c = open("/health");
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static HttpURLConnection open(String path) throws IOException {
        return (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
    }

    /**
     * One reply: the persona's instruction, the talk so far as alternating
     * turns (the last one is the user's), at most maxTokens of answer.
     */
    Reply ask(String system, List<String[]> turns, int maxTokens) throws IOException {
        return ask(system, turns, maxTokens, null);
    }

    /** As above; a JPEG image, when given, goes with the last turn for the model to look at. */
    Reply ask(String system, List<String[]> turns, int maxTokens, byte[] jpeg) throws IOException {
        wake(jpeg != null);
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            for (int i = 0; i < turns.size(); i++) {
                String[] t = turns.get(i);
                if (jpeg != null && i == turns.size() - 1) {
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                            .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg,
                                    android.util.Base64.NO_WRAP))));
                    parts.put(new JSONObject().put("type", "text").put("text", t[1]));
                    messages.put(new JSONObject().put("role", t[0]).put("content", parts));
                } else {
                    messages.put(new JSONObject().put("role", t[0]).put("content", t[1]));
                }
            }
            JSONObject body = new JSONObject()
                    .put("messages", messages)
                    .put("max_tokens", maxTokens)
                    .put("temperature", (double) temperature(context))
                    .put("top_p", 0.8)
                    // A small model copies its own last reply when the talk feeds it back: discourage that.
                    .put("repeat_penalty", 1.15)
                    .put("presence_penalty", 0.4)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            long started = System.nanoTime();
            HttpURLConnection c = open("/v1/chat/completions");
            c.setConnectTimeout(3000);
            c.setReadTimeout(120_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes("UTF-8");
            c.getOutputStream().write(bytes);
            int code = c.getResponseCode();
            InputStream in = code == 200 ? c.getInputStream() : c.getErrorStream();
            String answer = new String(readAll(in), "UTF-8");
            c.disconnect();
            if (code != 200) {
                throw new IOException("server answered " + code + ": " + answer);
            }
            JSONObject json = new JSONObject(answer);
            String text = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .optString("content", "");
            // A model that still thinks aloud: only what follows its thoughts is the answer.
            int end = text.lastIndexOf("</think>");
            if (end >= 0) {
                text = text.substring(end + 8);
            }
            double speed = 0;
            JSONObject timings = json.optJSONObject("timings");
            if (timings != null) {
                speed = timings.optDouble("predicted_per_second", 0);
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            lastSpeed = speed;
            Diag.log(context, String.format(java.util.Locale.ROOT, "%s: reply in %.1f s, %.1f tok/s", TAG,
                    seconds, speed));
            return new Reply(text.trim(), seconds, speed);
        } catch (org.json.JSONException e) {
            throw new IOException("bad reply: " + e.getMessage());
        } finally {
            sleepLater();
        }
    }

    private void sleepLater() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stop();
            }
        }, sleepMinutes(context) * 60_000L);
    }

    // ---- settings -------------------------------------------------------------

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("hora", Context.MODE_PRIVATE);
    }

    /** Whether the accelerator, when its file is there, is used; off lets the two be compared. */
    static boolean draftOn(Context c) {
        return prefs(c).getBoolean("brain_draft_on", false);
    }

    static void setDraftOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("brain_draft_on", on).apply();
    }

    static float temperature(Context c) {
        return prefs(c).getFloat("brain_temperature", 0.7f);
    }

    static void setTemperature(Context c, float v) {
        prefs(c).edit().putFloat("brain_temperature", v).apply();
    }

    static int replyTokens(Context c) {
        return prefs(c).getInt("brain_tokens", 90);
    }

    static void setReplyTokens(Context c, int v) {
        prefs(c).edit().putInt("brain_tokens", v).apply();
    }

    static int remembered(Context c) {
        return prefs(c).getInt("brain_remembered", 4);
    }

    static void setRemembered(Context c, int v) {
        prefs(c).edit().putInt("brain_remembered", v).apply();
    }

    static int sleepMinutes(Context c) {
        return prefs(c).getInt("brain_sleep", 5);
    }

    static void setSleepMinutes(Context c, int v) {
        prefs(c).edit().putInt("brain_sleep", v).apply();
    }

    /** The shared rules as written by the user, or the built-in ones. */
    static String rules(Context c) {
        String own = prefs(c).getString("brain_rules", null);
        return own != null ? own : SpeechLanguage.resources(c).getString(R.string.brain_rules);
    }

    static boolean rulesOwn(Context c) {
        return prefs(c).contains("brain_rules");
    }

    static void setRules(Context c, String text) {
        if (text == null) {
            prefs(c).edit().remove("brain_rules").apply();
        } else {
            prefs(c).edit().putString("brain_rules", text).apply();
        }
    }

    /** A voice's manner as written by the user, or the built-in one. */
    static String manner(Context c, int voice) {
        String own = prefs(c).getString("brain_manner_" + voice, null);
        if (own != null) {
            return own;
        }
        String[] all = SpeechLanguage.resources(c).getStringArray(R.array.brain_manner);
        return voice >= 1 && voice <= all.length ? all[voice - 1] : "";
    }

    static boolean mannerOwn(Context c, int voice) {
        return prefs(c).contains("brain_manner_" + voice);
    }

    static void setManner(Context c, int voice, String text) {
        if (text == null) {
            prefs(c).edit().remove("brain_manner_" + voice).apply();
        } else {
            prefs(c).edit().putString("brain_manner_" + voice, text).apply();
        }
    }

    /** The instruction a voice's mind starts from: its manner and the user's address, then the shared rules. */
    static String persona(Context c, int voice) {
        android.content.res.Resources r = SpeechLanguage.resources(c);
        String address = r.getString(Prefs.userFemale(c) ? R.string.brain_user_female : R.string.brain_user_male);
        String rules = rules(c);
        try {
            return String.format(rules, Cast.spokenName(c, voice), Cast.title(c, voice), manner(c, voice) + " " + address);
        } catch (java.util.IllegalFormatException e) {
            // Rules written by hand without the blanks: the manner goes in front.
            return manner(c, voice) + " " + address + " " + rules;
        }
    }

    synchronized void stop() {
        stopNow();
    }

    private void stopNow() {
        if (process != null) {
            process.destroy();
            process = null;
            Diag.mark(context, TAG + ": asleep");
        }
    }

    /** The end of the server's own log, for the journal when it fails. */
    String tail() {
        File f = new File(folder(), LOG);
        if (!f.isFile()) {
            return "";
        }
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            int from = Math.max(0, all.length - 600);
            return new String(all, from, all.length - from, "UTF-8").replace('\n', ' ');
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
