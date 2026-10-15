package com.k2fsa.sherpa.onnx;

// Every nested config must be non-null: the native side walks all of them.
public final class OnlineRecognizerConfig {
    public FeatureConfig featConfig = new FeatureConfig();
    public OnlineModelConfig modelConfig = new OnlineModelConfig();
    public OnlineLMConfig lmConfig = new OnlineLMConfig();
    public OnlineCtcFstDecoderConfig ctcFstDecoderConfig = new OnlineCtcFstDecoderConfig();
    public EndpointConfig endpointConfig = new EndpointConfig();
    public HomophoneReplacerConfig hr = new HomophoneReplacerConfig();
    public boolean enableEndpoint = true;
    public String decodingMethod = "greedy_search";
    public int maxActivePaths = 4;
    public String hotwordsFile = "";
    public float hotwordsScore = 1.5f;
    public String ruleFsts = "";
    public String ruleFars = "";
    public float blankPenalty = 0f;
}
