package com.musicplayer.controllers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Codifica/decodifica un par host:puerto en un código opaco para compartir.
 * Usa XOR con clave fija + Base64 URL-safe. No es seguridad criptográfica —
 * solo evita que la IP sea visible a plena vista.
 *
 * <p>Cuando el host es {@code bore.pub} (túnel bore), "bore.pub:" ocupa exactamente
 * 9 bytes — un múltiplo de 3 — así que su Base64 siempre cae en grupos completos y
 * el código resultante empieza siempre por los mismos {@value #PREFIX_LEN} caracteres,
 * sin importar el puerto. Esa parte no aporta ninguna información, así que
 * {@link #encode} la recorta antes de devolver el código (lo que el Master copia), y
 * {@link #decode} la reconstruye automáticamente si falta (lo que el Listener pega).
 * Los códigos de otros modos (UPnP, red local, serveo) nunca empiezan por este
 * prefijo, así que no se ven afectados.
 */
class RoomCode {

    private static final byte KEY = 0x4E;

    /** Prefijo constante y sin información que producen los códigos de host {@code bore.pub}. */
    private static final String PREFIX = "LCE8K2A-";
    private static final int PREFIX_LEN = PREFIX.length();

    static String encode(String host, int port) {
        byte[] raw = (host + ":" + port).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) raw[i] ^= KEY;
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return body.startsWith(PREFIX) ? body.substring(PREFIX_LEN) : body;
    }

    /** @return array [host, portString] o lanza {@link IllegalArgumentException} si el código es inválido. */
    static String[] decode(String code) {
        String trimmed = code.trim();
        // Se intenta primero asumiendo que es un código de bore.pub (con el prefijo
        // "LCE8K2A-" recortado por encode()): decodificar sin reponerlo primero daría
        // un resultado "válido" pero incorrecto (host truncado a partir del byte 6,
        // ya que "bore.pub:" tiene justo un colon más adelante en la cadena original).
        String[] withPrefix = tryDecode(PREFIX + trimmed);
        if (withPrefix != null && "bore.pub".equals(withPrefix[0])) return withPrefix;
        String[] direct = tryDecode(trimmed);
        if (direct != null) return direct;
        if (withPrefix != null) return withPrefix;
        throw new IllegalArgumentException("Código de sala inválido");
    }

    private static String[] tryDecode(String body) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(body);
            for (int i = 0; i < raw.length; i++) raw[i] ^= KEY;
            String plain = new String(raw, StandardCharsets.UTF_8);
            int colon = plain.lastIndexOf(':');
            if (colon < 0) return null;
            String host = plain.substring(0, colon);
            String portStr = plain.substring(colon + 1);
            Integer.parseInt(portStr); // valida que sea un número
            return new String[]{host, portStr};
        } catch (Exception e) {
            return null;
        }
    }
}
