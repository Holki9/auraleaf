/**
 * AuraLeaf Audio Module
 * Synthesizes a realistic, high-fidelity paper flip sound using Web Audio API.
 * 100% offline, zero external asset dependencies, zero latency.
 */
class PageAudio {
  constructor() {
    this.audioCtx = null;
    this.soundEnabled = localStorage.getItem('auraleaf_sound') !== 'false';
  }

  getAudioContext() {
    if (!this.audioCtx) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (AudioContext) {
        this.audioCtx = new AudioContext();
      }
    }
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume();
    }
    return this.audioCtx;
  }

  playFlip() {
    if (!this.soundEnabled) return;
    try {
      const ctx = this.getAudioContext();
      if (!ctx) return;

      const now = ctx.currentTime;

      // 1. White / Pink noise buffer for paper friction
      const bufferSize = ctx.sampleRate * 0.22; // 220ms
      const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
      const data = buffer.getChannelData(0);
      let lastOut = 0.0;
      for (let i = 0; i < bufferSize; i++) {
        const white = Math.random() * 2 - 1;
        // Pink-ish filter approximation
        lastOut = (lastOut * 0.65) + (white * 0.35);
        data[i] = lastOut;
      }

      const noise = ctx.createBufferSource();
      noise.buffer = buffer;

      // 2. Dynamic Bandpass filter (sweeps downwards as page bends and settles)
      const filter = ctx.createBiquadFilter();
      filter.type = 'bandpass';
      filter.Q.value = 2.2;
      filter.frequency.setValueAtTime(2600, now);
      filter.frequency.exponentialRampToValueAtTime(700, now + 0.18);

      // 3. Noise Gain Envelope (friction whoosh)
      const noiseGain = ctx.createGain();
      noiseGain.gain.setValueAtTime(0.001, now);
      noiseGain.gain.linearRampToValueAtTime(0.45, now + 0.025);
      noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.20);

      noise.connect(filter);
      filter.connect(noiseGain);
      noiseGain.connect(ctx.destination);

      // 4. Subtle low frequency paper snap / pop
      const osc = ctx.createOscillator();
      const oscGain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(95, now + 0.04);
      osc.frequency.exponentialRampToValueAtTime(45, now + 0.14);

      oscGain.gain.setValueAtTime(0.001, now);
      oscGain.gain.linearRampToValueAtTime(0.18, now + 0.05);
      oscGain.gain.exponentialRampToValueAtTime(0.001, now + 0.14);

      osc.connect(oscGain);
      oscGain.connect(ctx.destination);

      noise.start(now);
      osc.start(now + 0.04);
      noise.stop(now + 0.22);
      osc.stop(now + 0.16);
    } catch (e) {
      console.warn('Audio playback error:', e);
    }
  }

  toggleSound() {
    this.soundEnabled = !this.soundEnabled;
    localStorage.setItem('auraleaf_sound', this.soundEnabled ? 'true' : 'false');
    if (this.soundEnabled) {
      this.playFlip();
    }
    return this.soundEnabled;
  }

  isSoundEnabled() {
    return this.soundEnabled;
  }
}

window.pageAudio = new PageAudio();
