package com.musicplayer.controllers;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;

import java.net.InetAddress;
import java.util.Map;

/**
 * Abre y cierra un mapeo de puerto en el router via UPnP IGD.
 * Todos los métodos bloquean — llamar siempre desde un hilo de background.
 */
class UPnPHelper {

    private GatewayDevice gateway;
    private int mappedPort = -1;

    /**
     * Descubre el router, mapea {@code port} TCP y devuelve "IP_PÚBLICA:puerto".
     * Retorna {@code null} si UPnP no está disponible o falla.
     */
    String mapPort(int port) {
        try {
            GatewayDiscover discover = new GatewayDiscover();
            Map<InetAddress, GatewayDevice> gateways = discover.discover();
            if (gateways == null || gateways.isEmpty()) return null;

            gateway = gateways.values().iterator().next();
            if (!gateway.isConnected()) return null;

            String externalIp = gateway.getExternalIPAddress();
            if (externalIp == null || externalIp.isBlank()) return null;

            // Borrar mapeo anterior del mismo puerto si lo hubiera
            PortMappingEntry existing = new PortMappingEntry();
            if (gateway.getSpecificPortMappingEntry(port, "TCP", existing))
                gateway.deletePortMapping(port, "TCP");

            boolean ok = gateway.addPortMapping(
                port, port,
                gateway.getLocalAddress().getHostAddress(),
                "TCP", "Bardo Party"
            );
            if (!ok) return null;

            mappedPort = port;
            return externalIp + ":" + port;

        } catch (Exception e) {
            return null;
        }
    }

    /** Elimina el mapeo creado por {@link #mapPort}. No hace nada si no se creó ninguno. */
    void removeMapping() {
        if (gateway == null || mappedPort < 0) return;
        try { gateway.deletePortMapping(mappedPort, "TCP"); } catch (Exception ignored) {}
        mappedPort = -1;
    }
}
