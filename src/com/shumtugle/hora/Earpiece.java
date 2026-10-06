package com.shumtugle.hora;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Knows which outputs are private to one listener. Anything that could be
 * heard by the room (the phone speaker, a speaker over the air, a car) is not.
 */
final class Earpiece {
    // Constants newer than the lowest supported level; their values are fixed.
    private static final int TYPE_HEARING_AID = 23;
    private static final int TYPE_BLE_HEADSET = 26;

    private Earpiece() {
    }

    /** Whether a device plays into the listener's ears only. */
    static boolean isPrivate(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
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
