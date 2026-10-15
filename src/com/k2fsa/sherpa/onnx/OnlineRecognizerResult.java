package com.k2fsa.sherpa.onnx;

// Constructed by the native side through the (String, String[], float[], float[]) constructor.
public final class OnlineRecognizerResult {
    public final String text;
    public final String[] tokens;
    public final float[] timestamps;
    public final float[] ysProbs;

    public OnlineRecognizerResult(String text, String[] tokens, float[] timestamps, float[] ysProbs) {
        this.text = text;
        this.tokens = tokens;
        this.timestamps = timestamps;
        this.ysProbs = ysProbs;
    }
}
