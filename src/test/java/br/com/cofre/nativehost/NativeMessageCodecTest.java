package br.com.cofre.nativehost;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.*;

class NativeMessageCodecTest {
    @Test
    void roundTripAndMultipleMessages() throws Exception {
        var codec = new NativeMessageCodec();
        var output = new ByteArrayOutputStream();
        codec.write(
                output, "{\"texto\":\"ação\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        codec.write(output, new byte[] {1});
        var input = new ByteArrayInputStream(output.toByteArray());
        assertArrayEquals(
                "{\"texto\":\"ação\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                codec.read(input));
        assertArrayEquals(new byte[] {1}, codec.read(input));
        assertNull(codec.read(input));
    }

    @Test
    void rejectTruncationAndOversizedFrames() {
        var codec = new NativeMessageCodec();
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(new byte[] {1})));
        byte[] oversized =
                ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(65537).array();
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(oversized)));
        byte[] truncated = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(5).array();
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(truncated)));
    }
}
