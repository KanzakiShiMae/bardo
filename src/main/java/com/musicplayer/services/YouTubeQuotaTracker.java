package com.musicplayer.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Tracks estimated YouTube Data API v3 quota usage for the current Pacific day.
 *
 * <p><b>Enforced mode</b> (developer/employee key — starts with {@code "AIza"} and
 * ends with {@code "C5ik"}): limiter is always on at 1 000 units, cannot be disabled.
 *
 * <p><b>Normal mode</b>: limiter can be toggled and the daily cap is user-configurable.
 *
 * <p>Persisted to {@code %APPDATA%\Bardo\quota.json}. Resets at Pacific midnight.
 * Thread-safe: {@link #record} marshals to the FX thread automatically.
 */
public class YouTubeQuotaTracker {

    /** Fixed cap used when the key is in enforced mode. */
    public static final int ENFORCED_MAX = 1000;
    /** Default cap used in normal mode if the user has not set a custom value. */
    public static final int DEFAULT_MAX  = 1000;

    public static final int COST_SEARCH = 100;
    public static final int COST_LOOKUP = 1;

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    private static final Path QUOTA_FILE;
    static {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null)
            ? Path.of(appData, "Bardo")
            : Path.of(System.getProperty("user.home"), ".bardo");
        QUOTA_FILE = base.resolve("quota.json");
    }

    private final Gson            gson      = new GsonBuilder().setPrettyPrinting().create();
    private final IntegerProperty used      = new SimpleIntegerProperty(0);
    private final BooleanProperty exhausted = new SimpleBooleanProperty(false);

    /** True when the API key belongs to developers/employees — limiter cannot be disabled. */
    private final boolean enforced;
    private boolean       enabled;
    private int           dailyMax;
    private String        today;

    private static class QuotaDto {
        String  date;
        int     used;
        boolean enabled  = true;
        int     dailyMax = DEFAULT_MAX;
    }

    /**
     * @param apiKey the YouTube Data API key currently in use; used only to
     *               determine whether enforced mode applies.
     */
    public YouTubeQuotaTracker(String apiKey) {
        enforced = apiKey != null
            && apiKey.startsWith("AIza")
            && apiKey.endsWith("C5ik");
        today    = LocalDate.now(PACIFIC).toString();
        enabled  = true;
        dailyMax = enforced ? ENFORCED_MAX : DEFAULT_MAX;
        load();
        // Enforced keys always override whatever was persisted
        if (enforced) {
            enabled  = true;
            dailyMax = ENFORCED_MAX;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Records {@code units} consumed. No-op when limiter is disabled. Thread-safe. */
    public void record(int units) {
        if (!enabled) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> record(units));
            return;
        }
        checkRollover();
        used.set(Math.min(used.get() + units, dailyMax));
        if (used.get() >= dailyMax && !exhausted.get())
            exhausted.set(true);
        save();
    }

    /** Returns true only when the limiter is enabled AND the daily cap has been reached. */
    public boolean isExhausted() { return enabled && exhausted.get(); }

    public int  getUsedToday()  { return used.get(); }
    public int  getDailyMax()   { return dailyMax; }
    public boolean isEnabled()  { return enabled; }
    /** True when the developer/employee key is in use — {@link #setEnabled} is a no-op. */
    public boolean isEnforced() { return enforced; }

    public IntegerProperty usedTodayProperty() { return used; }
    public BooleanProperty exhaustedProperty() { return exhausted; }

    /**
     * Enables or disables the quota limiter.
     * No-op when {@link #isEnforced()} is true.
     */
    public void setEnabled(boolean val) {
        if (enforced) return;
        enabled = val;
        if (!enabled) exhausted.set(false); // lift any existing block
        save();
    }

    /**
     * Sets a custom daily cap (clamped to [1, 10 000]).
     * No-op when {@link #isEnforced()} is true.
     * Re-evaluates exhaustion immediately against the new cap.
     */
    public void setDailyMax(int max) {
        if (enforced) return;
        dailyMax = Math.max(1, Math.min(10_000, max));
        exhausted.set(used.get() >= dailyMax);
        save();
    }

    /** Seconds remaining until the next Pacific midnight reset. */
    public long getSecondsUntilReset() {
        ZonedDateTime now          = ZonedDateTime.now(PACIFIC);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(PACIFIC);
        return java.time.Duration.between(now, nextMidnight).getSeconds();
    }

    /** Formats {@link #getSecondsUntilReset()} as {@code HH:MM:SS}. */
    public String getTimeUntilResetString() {
        long s = getSecondsUntilReset();
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void checkRollover() {
        String now = LocalDate.now(PACIFIC).toString();
        if (!now.equals(today)) {
            today = now;
            used.set(0);
            exhausted.set(false);
            save();
        }
    }

    private void load() {
        try {
            if (!Files.exists(QUOTA_FILE)) return;
            QuotaDto dto = gson.fromJson(Files.readString(QUOTA_FILE), QuotaDto.class);
            if (dto == null) return;
            enabled  = dto.enabled;
            dailyMax = dto.dailyMax > 0 ? dto.dailyMax : DEFAULT_MAX;
            if (today.equals(dto.date)) {
                used.set(Math.min(dto.used, dailyMax));
                if (dto.used >= dailyMax) exhausted.set(true);
            }
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            QuotaDto dto = new QuotaDto();
            dto.date     = today;
            dto.used     = used.get();
            dto.enabled  = enabled;
            dto.dailyMax = dailyMax;
            Files.createDirectories(QUOTA_FILE.getParent());
            Files.writeString(QUOTA_FILE, gson.toJson(dto));
        } catch (IOException ignored) {}
    }
}
