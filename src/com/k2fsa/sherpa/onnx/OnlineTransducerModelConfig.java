package com.k2fsa.sherpa.onnx;

public final class OnlineTransducerModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String joiner = "";
    public QnnConfig qnnConfig = new QnnConfig();
}
