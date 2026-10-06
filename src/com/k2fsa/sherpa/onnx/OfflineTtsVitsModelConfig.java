package com.k2fsa.sherpa.onnx;

// Field names and types are read by the native library and must not change.
public final class OfflineTtsVitsModelConfig {
    public String model = "";
    public String lexicon = "";
    public String tokens = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 0.667f;
    public float noiseScaleW = 0.8f;
    public float lengthScale = 1.0f;
}
