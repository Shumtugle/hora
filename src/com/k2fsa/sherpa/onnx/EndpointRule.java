package com.k2fsa.sherpa.onnx;

// When an utterance counts as finished: enough silence at its end, or enough length.
public final class EndpointRule {
    public boolean mustContainNonSilence;
    public float minTrailingSilence;
    public float minUtteranceLength;

    public EndpointRule(boolean mustContainNonSilence, float minTrailingSilence, float minUtteranceLength) {
        this.mustContainNonSilence = mustContainNonSilence;
        this.minTrailingSilence = minTrailingSilence;
        this.minUtteranceLength = minUtteranceLength;
    }
}
