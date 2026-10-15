package com.k2fsa.sherpa.onnx;

// Every nested config must be non-null: the native side walks all of them.
public final class OnlineModelConfig {
    public OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
    public OnlineParaformerModelConfig paraformer = new OnlineParaformerModelConfig();
    public OnlineZipformer2CtcModelConfig zipformer2Ctc = new OnlineZipformer2CtcModelConfig();
    public OnlineNeMoCtcModelConfig neMoCtc = new OnlineNeMoCtcModelConfig();
    public OnlineToneCtcModelConfig toneCtc = new OnlineToneCtcModelConfig();
    public String tokens = "";
    public int numThreads = 2;
    public boolean debug = false;
    public String provider = "cpu";
    public String modelType = "";
    public String modelingUnit = "";
    public String bpeVocab = "";
}
