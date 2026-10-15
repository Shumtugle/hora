package com.k2fsa.sherpa.onnx;

// One utterance being heard. Free it with release() when done.
public final class OnlineStream {
    private long ptr;

    OnlineStream(long ptr) {
        this.ptr = ptr;
    }

    long ptr() {
        return ptr;
    }

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(ptr, samples, sampleRate);
    }

    public void inputFinished() {
        inputFinished(ptr);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);

    private native void inputFinished(long ptr);

    private native void delete(long ptr);
}
