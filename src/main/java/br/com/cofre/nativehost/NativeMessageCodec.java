package br.com.cofre.nativehost;

import java.io.*;
import java.nio.*;

/** Framing do Native Messaging: tamanho de 32 bits na ordem nativa, seguido de JSON UTF-8. */
public final class NativeMessageCodec {
    private static final int MAX_BYTES = 65536;

    public byte[] read(InputStream input) throws IOException {
        byte[] prefix = input.readNBytes(4);
        if (prefix.length == 0) return null;
        if (prefix.length != 4) throw new EOFException("Cabeçalho incompleto.");
        int length = ByteBuffer.wrap(prefix).order(ByteOrder.nativeOrder()).getInt();
        if (length <= 0 || length > MAX_BYTES) throw new IOException("Tamanho inválido.");
        byte[] message = input.readNBytes(length);
        if (message.length != length) throw new EOFException("Mensagem incompleta.");
        return message;
    }

    public void write(OutputStream output, byte[] message) throws IOException {
        if (message.length == 0 || message.length > MAX_BYTES)
            throw new IOException("Tamanho inválido.");
        output.write(
                ByteBuffer.allocate(4)
                        .order(ByteOrder.nativeOrder())
                        .putInt(message.length)
                        .array());
        output.write(message);
        output.flush();
    }
}
