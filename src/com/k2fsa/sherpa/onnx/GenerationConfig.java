package com.k2fsa.sherpa.onnx;

import java.util.Map;

// Field names and types are read by the native library and must not change.
public final class GenerationConfig {
    public float silenceScale = 0.2f;
    public float speed = 1.0f;
    public int sid = 0;
    public float[] referenceAudio;
    public int referenceSampleRate;
    public String referenceText = "";
    public int numSteps = 5;
    public Map<String, String> extra;
}
