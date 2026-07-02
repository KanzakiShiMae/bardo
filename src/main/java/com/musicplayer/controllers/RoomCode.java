package com.musicplayer.controllers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Codifica/decodifica un par host:puerto en un código opaco para compartir.
 * Usa XOR con clave fija + Base64 URL-safe. No es seguridad criptográfica —
 * solo evita que la IP sea visible a plena vista.
 */
class RoomCode {

    private static final byte KEY = 0x4E;

    static String encode(String host, int port) {
        byte[] raw = (host + ":" + port).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) raw[i] ^= KEY;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @return array [host, portString] o lanza {@link IllegalArgumentException} si el código es inválido. */
    static String[] decode(String code) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(code.trim());
            for (int i = 0; i < raw.length; i++) raw[i] ^= KEY;
            String plain = new String(raw, StandardCharsets.UTF_8);
            int colon = plain.lastIndexOf(':');
            if (colon < 0) throw new IllegalArgumentException();
            String host = plain.substring(0, colon);
            String portStr = plain.substring(colon + 1);
            Integer.parseInt(portStr); // valida que sea un número
            return new String[]{host, portStr};
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Código de sala inválido");
        } catch (Exception e) {
            throw new IllegalArgumentException("Código de sala inválido");
        }
    }
}
