package com.voxapps.audio

/**
 * The one sample rate every Vox voice pipeline runs at. Wake-word engines, STT, calibration and
 * voice-print extraction all assume the same 16 kHz mono stream — a component quietly opening the
 * microphone at another rate would feed every consumer downsampled garbage, so the figure lives
 * once, here.
 */
const val VOICE_SAMPLE_RATE_HZ = 16000
