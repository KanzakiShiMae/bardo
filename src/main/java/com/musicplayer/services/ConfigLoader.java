package com.musicplayer.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {

    private static final Properties props    = new Properties();
    private static final Properties appProps = new Properties();

    static {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(
                "/com/musicplayer/config.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Cannot load config.properties: " + e.getMessage());
        }
        try (InputStream in = ConfigLoader.class.getResourceAsStream(
                "/com/musicplayer/app.properties")) {
            if (in != null) appProps.load(in);
        } catch (IOException ignored) {}
    }

    public static String get(String key) {
        return props.getProperty(key);
    }

    public static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public static String getVersion() {
        return appProps.getProperty("version", "");
    }
}