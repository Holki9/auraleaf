package com.holki.auraleaf;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

public class SoundHelper {

    private SoundPool soundPool;
    private int flipSoundId = 0;
    private boolean soundEnabled = true;

    public SoundHelper(Context context) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build();

        generateAndLoadSound(context);
    }

    private void generateAndLoadSound(Context context) {
        try {
            int sampleRate = 44100;
            int durationMs = 200;
            int numSamples = (sampleRate * durationMs) / 1000;
            short[] samples = new short[numSamples];
            Random random = new Random();

            double lastOut = 0.0;
            for (int i = 0; i < numSamples; i++) {
                double progress = (double) i / numSamples;
                // Pink-ish noise
                double white = random.nextDouble() * 2.0 - 1.0;
                lastOut = (lastOut * 0.65) + (white * 0.35);

                // Amplitude envelope (sharp attack, exponential decay)
                double env = Math.pow(1.0 - progress, 2.5);
                double s = lastOut * env * 24000.0;

                // Subtle resonant frequency modulation
                double modulation = Math.sin(2.0 * Math.PI * (1200.0 - progress * 800.0) * (i / (double) sampleRate));
                s = (s * 0.7) + (s * modulation * 0.3);

                samples[i] = (short) Math.max(-32768, Math.min(32767, (int) s));
            }

            File tempWav = new File(context.getCacheDir(), "paper_flip.wav");
            writeWavFile(tempWav, samples, sampleRate);
            flipSoundId = soundPool.load(tempWav.getAbsolutePath(), 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeWavFile(File file, short[] samples, int sampleRate) throws IOException {
        int byteRate = sampleRate * 2;
        int dataSize = samples.length * 2;
        int chunkSize = 36 + dataSize;

        try (FileOutputStream out = new FileOutputStream(file)) {
            // RIFF header
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            out.write(intToBytes(chunkSize));
            out.write(new byte[]{'W', 'A', 'V', 'E'});
            // fmt chunk
            out.write(new byte[]{'f', 'm', 't', ' '});
            out.write(intToBytes(16)); // subchunk1size (16 for PCM)
            out.write(shortToBytes((short) 1)); // audio format 1 = PCM
            out.write(shortToBytes((short) 1)); // num channels = 1
            out.write(intToBytes(sampleRate));
            out.write(intToBytes(byteRate));
            out.write(shortToBytes((short) 2)); // block align
            out.write(shortToBytes((short) 16)); // bits per sample
            // data chunk
            out.write(new byte[]{'d', 'a', 't', 'a'});
            out.write(intToBytes(dataSize));

            byte[] buffer = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                buffer[i * 2] = (byte) (samples[i] & 0xFF);
                buffer[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
            }
            out.write(buffer);
        }
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private byte[] shortToBytes(short value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    public void playFlip() {
        if (soundEnabled && flipSoundId != 0 && soundPool != null) {
            soundPool.play(flipSoundId, 0.9f, 0.9f, 1, 0, 1.0f);
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
