package com.holki.auraleaf;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

public class SoundHelper {

    private SoundPool soundPool;
    private int flipSoundId = 0;
    private boolean soundEnabled = true;

    public SoundHelper(Context context) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build();

        try {
            // Load genuine studio recording of turning paper page
            flipSoundId = soundPool.load(context, R.raw.page_flip, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void playFlip() {
        if (soundEnabled && flipSoundId != 0 && soundPool != null) {
            soundPool.play(flipSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    public boolean toggleSound() {
        soundEnabled = !soundEnabled;
        if (soundEnabled) playFlip();
        return soundEnabled;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
