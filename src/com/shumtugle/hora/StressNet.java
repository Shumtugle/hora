package com.shumtugle.hora;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Guesses the stressed letter of a word nobody has listed: a small character
 * transformer with rotary positions, run here in plain Java so it needs no
 * inference runtime. Letters in, one of three labels per letter out: none,
 * primary stress, secondary stress. Only a confident primary stress is used;
 * when the network is unsure the word stays unmarked.
 */
final class StressNet {
    private static final String WEIGHTS = "lexicons/stress-net.bin";
    private static final String VOCAB = "lexicons/stress-net-vocab.txt";
    /** Words whose stress may fall on an unwritten yo: spelling alone would mislead the guess. */
    private static final String HIDDEN_YO = "lexicons/stress-yo-maybe.txt";
    private static final int UNK = 1;
    private static final int BOS = 2;
    private static final int EOS = 3;
    private static final int PRIMARY = 1;
    /** Lowest confidence at which a guess is trusted. */
    private static final float MIN_SCORE = 0.55f;

    private static StressNet shared;
    private static boolean failed;

    private final int hidden;
    private final int inter;
    private final int heads;
    private final int headDim;
    private final int positions;
    private final Map<Character, Integer> ids = new HashMap<Character, Integer>();
    private final java.util.Set<String> hiddenYo = new java.util.HashSet<String>();

    private final float[] wordEmb;
    private final float[] typeEmb;
    private final float[] embG;
    private final float[] embB;
    private final float[] pos;
    private final Layer[] layers;
    private final float[] outW;
    private final float[] outB;

    private static final class Layer {
        float[] wq, bq, wk, bk, wv, bv, wo, bo, g1, b1, wi, bi, w2, b2, g2, b2n;
    }

    private StressNet(ByteBuffer b, String[] vocab) {
        int v = b.getInt();
        hidden = b.getInt();
        inter = b.getInt();
        int n = b.getInt();
        heads = b.getInt();
        positions = b.getInt();
        headDim = hidden / heads;
        wordEmb = NetMath.take(b, v * hidden);
        typeEmb = NetMath.take(b, hidden);
        embG = NetMath.take(b, hidden);
        embB = NetMath.take(b, hidden);
        pos = NetMath.take(b, positions * headDim);
        layers = new Layer[n];
        for (int i = 0; i < n; i++) {
            Layer l = new Layer();
            l.wq = NetMath.take(b, hidden * hidden);
            l.bq = NetMath.take(b, hidden);
            l.wk = NetMath.take(b, hidden * hidden);
            l.bk = NetMath.take(b, hidden);
            l.wv = NetMath.take(b, hidden * hidden);
            l.bv = NetMath.take(b, hidden);
            l.wo = NetMath.take(b, hidden * hidden);
            l.bo = NetMath.take(b, hidden);
            l.g1 = NetMath.take(b, hidden);
            l.b1 = NetMath.take(b, hidden);
            l.wi = NetMath.take(b, hidden * inter);
            l.bi = NetMath.take(b, inter);
            l.w2 = NetMath.take(b, inter * hidden);
            l.b2 = NetMath.take(b, hidden);
            l.g2 = NetMath.take(b, hidden);
            l.b2n = NetMath.take(b, hidden);
            layers[i] = l;
        }
        outW = NetMath.take(b, hidden * 3);
        outB = NetMath.take(b, 3);
        for (int i = 0; i < vocab.length; i++) {
            if (vocab[i].length() == 1) {
                ids.put(vocab[i].charAt(0), i);
            }
        }
    }

    /** The shared network, loaded once; null if its files are missing or broken. */
    static synchronized StressNet get(Context c) {
        if (shared == null && !failed) {
            try {
                shared = load(c);
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
        }
        return shared;
    }

    private static StressNet load(Context c) throws IOException {
        byte[] raw = readAll(c.getAssets().open(WEIGHTS));
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'H' || b.get() != 'S' || b.get() != 'N' || b.get() != '1') {
            throw new IOException("bad stress network");
        }
        java.util.List<String> vocab = lines(c, VOCAB);
        StressNet net = new StressNet(b, vocab.toArray(new String[0]));
        for (String w : lines(c, HIDDEN_YO)) {
            if (!w.isEmpty()) {
                net.hiddenYo.add(w);
            }
        }
        return net;
    }

    private static java.util.List<String> lines(Context c, String asset) throws IOException {
        java.util.List<String> out = new java.util.ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(asset), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } finally {
            r.close();
        }
        return out;
    }

    /**
     * Index of the stressed letter in a lower-case word, or -1 when the
     * network is unsure or the word does not fit.
     */
    int stressIndex(String word) {
        int n = word.length() + 2;
        if (n > positions || hiddenYo.contains(word)) {
            return -1;
        }
        int[] t = new int[n];
        t[0] = BOS;
        t[n - 1] = EOS;
        for (int i = 0; i < word.length(); i++) {
            Integer id = ids.get(word.charAt(i));
            t[i + 1] = id == null ? UNK : id;
        }
        float[] logits = forward(t);
        int best = -1;
        float bestScore = 0;
        for (int i = 1; i < n - 1; i++) {
            float l0 = logits[i * 3];
            float l1 = logits[i * 3 + 1];
            float l2 = logits[i * 3 + 2];
            float m = Math.max(l0, Math.max(l1, l2));
            if (l1 < m) {
                continue;
            }
            double e0 = Math.exp(l0 - m);
            double e1 = Math.exp(l1 - m);
            double e2 = Math.exp(l2 - m);
            float p = (float) (e1 / (e0 + e1 + e2));
            if (p >= MIN_SCORE && p > bestScore) {
                bestScore = p;
                best = i - 1;
            }
        }
        return best;
    }

    /** Label scores for every position, [n][3] flattened. */
    float[] forward(int[] t) {
        int n = t.length;
        float[] x = new float[n * hidden];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < hidden; j++) {
                x[i * hidden + j] = wordEmb[t[i] * hidden + j] + typeEmb[j];
            }
        }
        NetMath.layerNorm(x, n, hidden, embG, embB);
        for (Layer l : layers) {
            float[] q = NetMath.linear(x, n, hidden, hidden, l.wq, l.bq);
            float[] k = NetMath.linear(x, n, hidden, hidden, l.wk, l.bk);
            float[] v = NetMath.linear(x, n, hidden, hidden, l.wv, l.bv);
            rotate(q, n);
            rotate(k, n);
            float[] ctx = attend(q, k, v, n);
            float[] a = NetMath.linear(ctx, n, hidden, hidden, l.wo, l.bo);
            for (int i = 0; i < a.length; i++) {
                a[i] += x[i];
            }
            NetMath.layerNorm(a, n, hidden, l.g1, l.b1);
            float[] mid = NetMath.linear(a, n, hidden, inter, l.wi, l.bi);
            NetMath.gelu(mid);
            float[] o = NetMath.linear(mid, n, inter, hidden, l.w2, l.b2);
            for (int i = 0; i < o.length; i++) {
                o[i] += a[i];
            }
            NetMath.layerNorm(o, n, hidden, l.g2, l.b2n);
            x = o;
        }
        return NetMath.linear(x, n, hidden, 3, outW, outB);
    }

    /** Rotary positions: each head's pairs of values turn by an angle that grows with position. */
    private void rotate(float[] q, int n) {
        int half = headDim / 2;
        for (int i = 0; i < n; i++) {
            for (int h = 0; h < heads; h++) {
                int base = i * hidden + h * headDim;
                for (int j = 0; j < half; j++) {
                    float sin = pos[i * headDim + j];
                    float cos = pos[i * headDim + half + j];
                    float even = q[base + 2 * j];
                    float odd = q[base + 2 * j + 1];
                    q[base + 2 * j] = even * cos - odd * sin;
                    q[base + 2 * j + 1] = odd * cos + even * sin;
                }
            }
        }
    }

    private float[] attend(float[] q, float[] k, float[] v, int n) {
        float[] out = new float[n * hidden];
        float scale = (float) (1.0 / Math.sqrt(headDim));
        float[] s = new float[n];
        for (int h = 0; h < heads; h++) {
            int off = h * headDim;
            for (int i = 0; i < n; i++) {
                float max = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < n; j++) {
                    float d = 0;
                    for (int e = 0; e < headDim; e++) {
                        d += q[i * hidden + off + e] * k[j * hidden + off + e];
                    }
                    s[j] = d * scale;
                    max = Math.max(max, s[j]);
                }
                float sum = 0;
                for (int j = 0; j < n; j++) {
                    s[j] = (float) Math.exp(s[j] - max);
                    sum += s[j];
                }
                for (int j = 0; j < n; j++) {
                    float w = s[j] / sum;
                    for (int e = 0; e < headDim; e++) {
                        out[i * hidden + off + e] += w * v[j * hidden + off + e];
                    }
                }
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
