package com.k2fsa.sherpa.onnx;

// Thin handle around the native streaming recognizer. Not thread-safe: call from one thread.
public final class OnlineRecognizer {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;

    public OnlineRecognizer(OnlineRecognizerConfig config) {
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalStateException("recognizer init failed");
        }
    }

    /** A new utterance; hotwords may be empty. */
    public OnlineStream createStream(String hotwords) {
        return new OnlineStream(createStream(ptr, hotwords == null ? "" : hotwords));
    }

    public boolean isReady(OnlineStream s) {
        return isReady(ptr, s.ptr());
    }

    public void decode(OnlineStream s) {
        decode(ptr, s.ptr());
    }

    public boolean isEndpoint(OnlineStream s) {
        return isEndpoint(ptr, s.ptr());
    }

    public void reset(OnlineStream s) {
        reset(ptr, s.ptr());
    }

    public OnlineRecognizerResult getResult(OnlineStream s) {
        return getResult(ptr, s.ptr());
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native long newFromFile(OnlineRecognizerConfig config);

    private native void delete(long ptr);

    private native long createStream(long ptr, String hotwords);

    private native void reset(long ptr, long streamPtr);

    private native void decode(long ptr, long streamPtr);

    private native boolean isEndpoint(long ptr, long streamPtr);

    private native boolean isReady(long ptr, long streamPtr);

    private native OnlineRecognizerResult getResult(long ptr, long streamPtr);
}
