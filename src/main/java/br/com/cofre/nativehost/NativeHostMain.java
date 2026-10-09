package br.com.cofre.nativehost;

import br.com.cofre.ipc.PrivateEndpoint;
import br.com.cofre.ipc.StatusClient;

/** stdout é reservado ao protocolo binário; o host encaminha pedidos sem abrir o banco. */
public final class NativeHostMain {
    public static void main(String[] args) throws Exception {
        String allowedOrigin = System.getProperty("cofre.extensionOrigin", "");
        if (allowedOrigin.isEmpty() || args.length == 0 || !allowedOrigin.equals(args[0])) {
            return;
        }
        var client = new StatusClient(PrivateEndpoint.defaultFile());
        var protocol = new StatusProtocol(() -> "APP_CLOSED", origin -> java.util.List.of(), FillProtocol.validationOnly());
        var codec = new NativeMessageCodec();
        byte[] input;
        while ((input = codec.read(System.in)) != null) {
            byte[] checked = protocol.handle(input);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            codec.write(
                    System.out,
                    json.readTree(checked).path("ok").asBoolean() ? client.query(input) : checked);
        }
    }
}
