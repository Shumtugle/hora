package com.shumtugle.hora;

/** Arithmetic shared by the small networks: dense layers, layer norm, GELU, half floats. */
final class NetMath {
    private static final float EPS = 1e-12f;

    private NetMath() {
    }

    /** Row-major input [n][in] times weights [in][out], plus bias. */
    static float[] linear(float[] x, int n, int in, int out, float[] w, float[] b) {
        float[] y = new float[n * out];
        for (int i = 0; i < n; i++) {
            int yo = i * out;
            System.arraycopy(b, 0, y, yo, out);
            for (int k = 0; k < in; k++) {
                float xv = x[i * in + k];
                if (xv == 0f) {
                    continue;
                }
                int wo = k * out;
                for (int j = 0; j < out; j++) {
                    y[yo + j] += xv * w[wo + j];
                }
            }
        }
        return y;
    }

    static void layerNorm(float[] x, int n, int dim, float[] g, float[] b) {
        for (int i = 0; i < n; i++) {
            int o = i * dim;
            float mean = 0;
            for (int j = 0; j < dim; j++) {
                mean += x[o + j];
            }
            mean /= dim;
            float var = 0;
            for (int j = 0; j < dim; j++) {
                float d = x[o + j] - mean;
                var += d * d;
            }
            var /= dim;
            float inv = (float) (1.0 / Math.sqrt(var + EPS));
            for (int j = 0; j < dim; j++) {
                x[o + j] = (x[o + j] - mean) * inv * g[j] + b[j];
            }
        }
    }

    static void addInPlace(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            a[i] += b[i];
        }
    }

    /** Exact GELU through the error function. */
    static void gelu(float[] x) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            x[i] = (float) (0.5 * v * (1.0 + erf(v / Math.sqrt(2.0))));
        }
    }

    /** Error function through a Chebyshev fit of its complement, accurate to about 1e-7. */
    static double erf(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double y = 1.0 - t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? y : -y;
    }

    /** IEEE half float to float. */
    static float half(short h) {
        int bits = h & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exp = (bits >>> 10) & 0x1f;
        int mant = bits & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            float v = mant / 1024f / 16384f;
            return sign != 0 ? -v : v;
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
    }

    static float[] take(java.nio.ByteBuffer b, int count) {
        float[] a = new float[count];
        b.asFloatBuffer().get(a);
        b.position(b.position() + count * 4);
        return a;
    }
}
