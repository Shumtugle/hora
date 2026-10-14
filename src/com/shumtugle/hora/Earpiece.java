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

    /** The first private output connected now, or null. */
    static AudioDeviceInfo find(AudioManager audio) {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isPrivate(d)) {
                return d;
            }
        }
        return null;
    }

    static boolean present(AudioManager audio) {
        return find(audio) != null;
    }
}
