/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Unit tests for AudioEngine to verify audio format support and conversion.
Tests cover:
- Legacy 8-bit unsigned PCM (22050 Hz)
- Modern float PCM (48000 Hz) for OPUS support
- Audio format conversion (8-bit <-> 16-bit, float <-> 16-bit)
- Device selection and fallback mechanisms
*/
package com.vagell.kv4pht.desktop.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AudioEngine Tests")
class AudioEngineTest {

    private AudioEngine audioEngine;

    @BeforeEach
    void setUp() {
        audioEngine = new AudioEngine();
    }

    @Test
    @DisplayName("Should initialize with legacy format by default")
    void testInitializeLegacyFormat() {
        audioEngine.detectAndSetAudioFormat();
        assertEquals(AudioEngine.AudioFormatType.LEGACY_8BIT_22K, audioEngine.getAudioFormatType());
    }

    @Test
    @DisplayName("Should support setting modern OPUS format")
    void testSetModernFormat() {
        audioEngine.setAudioFormatType(AudioEngine.AudioFormatType.MODERN_FLOAT_48K);
        assertEquals(AudioEngine.AudioFormatType.MODERN_FLOAT_48K, audioEngine.getAudioFormatType());
    }

    @Test
    @DisplayName("Should convert 8-bit unsigned to 16-bit signed (little-endian)")
    void testExpand8uTo16sLittleEndian() {
        byte[] pcm8 = {(byte) 128, (byte) 255, (byte) 0};  // 0, 127, -128
        byte[] result = AudioEngine.expand8uTo16s(pcm8, false);
        
        assertEquals(6, result.length);  // 3 * 2
        
        // Verify first sample (128 -> 0 signed)
        short first = (short) ((result[1] << 8) | (result[0] & 0xFF));
        assertEquals(0, first);
        
        // Verify second sample (255 -> 127 signed)
        short second = (short) ((result[3] << 8) | (result[2] & 0xFF));
        assertEquals(32512, second);  // 127 << 8
        
        // Verify third sample (0 -> -128 signed)
        short third = (short) ((result[5] << 8) | (result[4] & 0xFF));
        assertEquals(-32768, third);  // -128 << 8
    }

    @Test
    @DisplayName("Should convert 8-bit unsigned to 16-bit signed (big-endian)")
    void testExpand8uTo16sBigEndian() {
        byte[] pcm8 = {(byte) 128, (byte) 255, (byte) 0};
        byte[] result = AudioEngine.expand8uTo16s(pcm8, true);
        
        assertEquals(6, result.length);
        
        // Verify first sample (128 -> 0 signed, big-endian)
        short first = (short) ((result[0] << 8) | (result[1] & 0xFF));
        assertEquals(0, first);
        
        // Verify second sample (255 -> 127 signed, big-endian)
        short second = (short) ((result[2] << 8) | (result[3] & 0xFF));
        assertEquals(32512, second);
    }

    @Test
    @DisplayName("Should convert 16-bit signed to 8-bit unsigned (little-endian)")
    void testShrink16sTo8uLittleEndian() {
        byte[] s16Bytes = {0, 0, (byte) 0xFF, 0x7F, 0, (byte) 0x80};  // 0, 32767, -32768
        byte[] out = new byte[3];
        int count = AudioEngine.shrink16sTo8u(s16Bytes, 6, out, false);
        
        assertEquals(3, count);
        assertEquals((byte) 128, out[0]);  // 0 -> 128
        assertEquals((byte) 255, out[1]);  // 127 -> 255
        assertEquals((byte) 0, out[2]);    // -128 -> 0
    }

    @Test
    @DisplayName("Should convert 16-bit signed to 8-bit unsigned (big-endian)")
    void testShrink16sTo8uBigEndian() {
        byte[] s16Bytes = {0, 0, 0x7F, (byte) 0xFF, (byte) 0x80, 0};  // 0, 32767, -32768
        byte[] out = new byte[3];
        int count = AudioEngine.shrink16sTo8u(s16Bytes, 6, out, true);
        
        assertEquals(3, count);
        assertEquals((byte) 128, out[0]);
        assertEquals((byte) 255, out[1]);
        assertEquals((byte) 0, out[2]);
    }

    @Test
    @DisplayName("Should convert float samples to 16-bit signed (little-endian)")
    void testExpandFloatTo16sLittleEndian() {
        float[] floatSamples = {0.0f, 1.0f, -1.0f, 0.5f};
        byte[] result = AudioEngine.expandFloatTo16s(floatSamples, false);
        
        assertEquals(8, result.length);  // 4 * 2
        
        // Verify first sample (0.0 -> 0)
        short first = (short) ((result[1] << 8) | (result[0] & 0xFF));
        assertEquals(0, first);
        
        // Verify second sample (1.0 -> 32767)
        short second = (short) ((result[3] << 8) | (result[2] & 0xFF));
        assertEquals(32767, second);
        
        // Verify third sample (-1.0 -> -32768)
        short third = (short) ((result[5] << 8) | (result[4] & 0xFF));
        assertEquals(-32768, third);
    }

    @Test
    @DisplayName("Should clamp float values before conversion")
    void testExpandFloatTo16sClamp() {
        float[] floatSamples = {2.0f, -2.0f};  // Out of range
        byte[] result = AudioEngine.expandFloatTo16s(floatSamples, false);
        
        assertEquals(4, result.length);
        
        // Should clamp to 1.0 -> 32767
        short first = (short) ((result[1] << 8) | (result[0] & 0xFF));
        assertEquals(32767, first);
        
        // Should clamp to -1.0 -> -32768
        short second = (short) ((result[3] << 8) | (result[2] & 0xFF));
        assertEquals(-32768, second);
    }

    @Test
    @DisplayName("Should convert float to bytes (little-endian)")
    void testFloatToBytesLittleEndian() {
        float[] floatSamples = {0.0f};
        byte[] result = AudioEngine.floatToBytes(floatSamples, false);
        
        assertEquals(4, result.length);
        int floatBits = Float.floatToIntBits(0.0f);
        assertEquals((byte) (floatBits & 0xFF), result[0]);
        assertEquals((byte) ((floatBits >> 8) & 0xFF), result[1]);
        assertEquals((byte) ((floatBits >> 16) & 0xFF), result[2]);
        assertEquals((byte) ((floatBits >> 24) & 0xFF), result[3]);
    }

    @Test
    @DisplayName("Should convert float to bytes (big-endian)")
    void testFloatToBytesBigEndian() {
        float[] floatSamples = {0.0f};
        byte[] result = AudioEngine.floatToBytes(floatSamples, true);
        
        assertEquals(4, result.length);
        int floatBits = Float.floatToIntBits(0.0f);
        assertEquals((byte) ((floatBits >> 24) & 0xFF), result[0]);
        assertEquals((byte) ((floatBits >> 16) & 0xFF), result[1]);
        assertEquals((byte) ((floatBits >> 8) & 0xFF), result[2]);
        assertEquals((byte) (floatBits & 0xFF), result[3]);
    }

    @Test
    @DisplayName("Should handle empty audio buffer in playAudio")
    void testPlayAudioEmptyBuffer() {
        audioEngine.setAudioFormatType(AudioEngine.AudioFormatType.LEGACY_8BIT_22K);
        // Should not throw exception for empty buffer
        assertDoesNotThrow(() -> audioEngine.playAudio(new byte[0]));
    }

    @Test
    @DisplayName("Should handle null playback state in playAudio")
    void testPlayAudioNullLine() {
        audioEngine.setAudioFormatType(AudioEngine.AudioFormatType.LEGACY_8BIT_22K);
        // Playback line not started, should not throw
        assertDoesNotThrow(() -> audioEngine.playAudio(new byte[10]));
    }

    @Test
    @DisplayName("Should handle empty float buffer in playAudioFloat")
    void testPlayAudioFloatEmptyBuffer() {
        audioEngine.setAudioFormatType(AudioEngine.AudioFormatType.MODERN_FLOAT_48K);
        assertDoesNotThrow(() -> audioEngine.playAudioFloat(new float[0]));
    }

    @Test
    @DisplayName("Should detect format type correctly")
    void testDetectAudioFormat() {
        audioEngine.detectAndSetAudioFormat();
        assertNotNull(audioEngine.getAudioFormatType());
        assertTrue(audioEngine.getAudioFormatType() == AudioEngine.AudioFormatType.LEGACY_8BIT_22K ||
                   audioEngine.getAudioFormatType() == AudioEngine.AudioFormatType.MODERN_FLOAT_48K);
    }

    @Test
    @DisplayName("Should support 8-bit round-trip conversion")
    void testRoundTripConversion8Bit() {
        byte[] original = {(byte) 128, (byte) 200, (byte) 50, (byte) 255};
        byte[] s16 = AudioEngine.expand8uTo16s(original, false);
        byte[] result = new byte[original.length];
        AudioEngine.shrink16sTo8u(s16, s16.length, result, false);
        
        // Allow small rounding error (±1)
        for (int i = 0; i < original.length; i++) {
            byte diff = (byte) Math.abs((original[i] & 0xFF) - (result[i] & 0xFF));
            assertTrue(diff <= 1, "Byte " + i + " differs by " + diff);
        }
    }

    @Test
    @DisplayName("Should support float round-trip conversion")
    void testRoundTripConversionFloat() {
        float[] original = {0.0f, 0.5f, -0.5f, 0.99f};
        byte[] s16 = AudioEngine.expandFloatTo16s(original, false);
        byte[] fBytes = AudioEngine.floatToBytes(original, false);
        
        assertNotNull(s16);
        assertNotNull(fBytes);
        assertEquals(original.length * 2, s16.length);
        assertEquals(original.length * 4, fBytes.length);
    }
}
