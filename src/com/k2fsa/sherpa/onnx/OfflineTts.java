package com.k2fsa.sherpa.onnx;

// Thin handle around the native synthesizer. Not thread-safe: call from one thread.
public final class OfflineTts {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    public OfflineTts(OfflineTtsConfig config) {
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalStateException("synthesizer init failed");
        }
    }

    public int sampleRate() {
        return getSampleRate(ptr);
    }

    public GeneratedAudio generate(String text, int speakerId, float speed) {
        return generateImpl(ptr, text, speakerId, speed);
    }

    public GeneratedAudio generate(String text, GenerationConfig config) {
        return generateWithConfigImpl(ptr, text, config, null);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native long newFromFile(OfflineTtsConfig config);
    private native void delete(long ptr);
    private native int getSampleRate(long ptr);
    private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
    // The last argument is an optional progress callback; null disables it.
    private native GeneratedAudio generateWithConfigImpl(long ptr, String text,
            GenerationConfig config, Object callback);
}
