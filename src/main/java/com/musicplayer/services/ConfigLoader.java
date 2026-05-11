package com.musicplayer.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigLoader {

    private static final Properties props = new Properties();

    static {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(
                "/com/musicplayer/config.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Cannot load config.properties: " + e.getMessage());
        }
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
}