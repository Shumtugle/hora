package com.shumtugle.hora;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Knows which outputs may be one's own headphones: wired, USB, wireless, a hearing aid. The phone's
 * own speaker never is. Whose ears a found output really is, is decided in Ears.
 */
final class Earpiece {
    // Constants newer than the lowest supported level; their values are fixed.
    private static final int TYPE_HEARING_AID = 23;
    private static final int TYPE_BLE_HEADSET = 26;

    private Earpiece() {
    }

    /** Whether a device may play into the listener's ears; Ears decides whether it does. */
    static boolean isPrivate(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case TYPE_HEARING_AID:
            case TYPE_BLE_HEADSET:
                return true;
            default:
                return false;
        }
    }

    /**
     * The private output the sound would go to now, or null. A pair of headphones can show as more
     * than one output (an older and a newer wireless kind, say) while only one of them plays; sound
     * sent to the silent one is lost without a word. Where the phone can tell where Hora's speech
     * would be routed, that output is taken, and only if it is private; elsewhere the first private
     * output connected.
     */
    static AudioDeviceInfo find(AudioManager audio) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                java.util.List<AudioDeviceInfo> routed = audio.getAudioDevicesForAttributes(SPEECH);
                if (routed != null && !routed.isEmpty()) {
                    // Every output the speech would reach must be one's own: sound sent to the
                    // headphones and the speaker at once is not private.
                    AudioDeviceInfo ear = null;
                    for (AudioDeviceInfo d : routed) {
                        if (!isPrivate(d)) {
                            return null;
                        }
                        if (ear == null) {
                            ear = d;
                        }
                    }
                    return ear;
                }
            } catch (RuntimeException e) {
                // Not told: the plain way below.
            }
        }
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isPrivate(d)) {
                return d;
            }
        }
        return null;
    }

    /** How Hora's own speech is marked, as the reader of notifications plays it (16: the assistant's usage). */
    private static final android.media.AudioAttributes SPEECH = new android.media.AudioAttributes.Builder()
            .setUsage(16)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    /** A short word for the journal: what kind of output it is, never its name. */
    static String kind(AudioDeviceInfo d) {
        if (d == null) {
            return "none";
        }
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "wired";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "usb";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "bluetooth";
            case TYPE_HEARING_AID:
                return "hearing aid";
            case TYPE_BLE_HEADSET:
                return "bluetooth le";
            default:
                return "type " + d.getType();
        }
    }

    static boolean present(AudioManager audio) {
        return find(audio) != null;
    }
}
