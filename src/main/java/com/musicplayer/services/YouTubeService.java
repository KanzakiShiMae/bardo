package com.musicplayer.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicplayer.models.Song;
import com.musicplayer.models.YouTubePlaylistInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cliente de la YouTube Data API v3 que expone búsquedas de vídeos y playlists,
 * obtención paginada de los elementos de una playlist y resolución directa por ID.
 *
 * <p>Todas las operaciones de red se ejecutan en hilos del pool común mediante
 * {@link java.util.concurrent.CompletableFuture#supplyAsync}; las actualizaciones
 * de UI deben envolverse en {@code Platform.runLater()}.
 *
 * <p>Los errores HTTP (código no 2xx) y de red se propagan como {@link RuntimeException}
 * dentro del {@code CompletableFuture}; el llamador debe encadenar {@code .exceptionally()}
 * para tratarlos (ver usos en {@code MainController}).
 *
 * <p>Métodos públicos:
 * <ul>
 *   <li>{@link #search} — búsqueda de vídeos por texto (categoría música).</li>
 *   <li>{@link #searchPlaylists} — búsqueda de playlists con recuento de ítems.</li>
 *   <li>{@link #getPlaylistItems} — obtiene todas las páginas de una playlist.</li>
 *   <li>{@link #getVideoById} — resuelve un vídeo por ID, incluye duración ISO 8601.</li>
 *   <li>{@link #getPlaylistById} — resuelve una playlist por su ID.</li>
 * </ul>
 */
public class YouTubeService {

    private static final String  BASE_URL         = "https://www.googleapis.com/youtube/v3";
    private static final Pattern DURATION_PATTERN = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
    private final String apiKey;
    private final OkHttpClient http;
    private final YouTubeQuotaTracker quotaTracker;

    public YouTubeService(String apiKey, YouTubeQuotaTracker quotaTracker) {
        this.apiKey        = apiKey;
        this.quotaTracker  = quotaTracker;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    // ── Video search ──────────────────────────────────────────────────────────

    public CompletableFuture<ObservableList<Song>> search(String query, int maxResults) {
        return CompletableFuture.supplyAsync(() -> {
            if (quotaTracker.isExhausted()) return FXCollections.observableArrayList();
            String url = BASE_URL + "/search"
                + "?part=snippet"
                + "&type=video"
                + "&videoCategoryId=10"
                + "&q=" + encode(query)
                + "&maxResults=" + maxResults
                + "&key=" + apiKey;
            quotaTracker.record(YouTubeQuotaTracker.COST_SEARCH);
            try {
                ObservableList<Song> songs = parseVideoSearchResults(rawGet(url));
                // +1 unit per ≤50 results to fetch durations
                if (!songs.isEmpty())
                    applyDurations(songs, fetchDurations(songs.stream().map(Song::getVideoId).collect(Collectors.toList())));
                return songs;
            } catch (RuntimeException e) {
                System.err.println("YouTube API error: " + e.getMessage());
                return FXCollections.observableArrayList();
            }
        });
    }

    // ── Playlist search ───────────────────────────────────────────────────────

    public CompletableFuture<ObservableList<YouTubePlaylistInfo>> searchPlaylists(String query, int maxResults) {
        return CompletableFuture.supplyAsync(() -> {
            if (quotaTracker.isExhausted()) return FXCollections.observableArrayList();
            String url = BASE_URL + "/search"
                + "?part=snippet"
                + "&type=playlist"
                + "&q=" + encode(query)
                + "&maxResults=" + maxResults
                + "&key=" + apiKey;

            quotaTracker.record(YouTubeQuotaTracker.COST_SEARCH);
            String body = rawGet(url);
            List<YouTubePlaylistInfo> playlists = parsePlaylistSearchResults(body);

            // Batch-fetch item counts
            if (!playlists.isEmpty()) {
                String ids = playlists.stream()
                    .map(YouTubePlaylistInfo::getPlaylistId)
                    .collect(Collectors.joining(","));
                String countUrl = BASE_URL + "/playlists"
                    + "?part=contentDetails"
                    + "&id=" + ids
                    + "&key=" + apiKey;

                try {
                    quotaTracker.record(YouTubeQuotaTracker.COST_LOOKUP);
                    String countBody = rawGet(countUrl);
                    Map<String, Integer> counts = parseItemCounts(countBody);
                    playlists.forEach(p -> {
                        Integer c = counts.get(p.getPlaylistId());
                        if (c != null) p.setItemCount(c);
                    });
                } catch (RuntimeException ignored) {}
            }

            return FXCollections.observableArrayList(playlists);
        });
    }

    // ── Playlist items (all pages) ────────────────────────────────────────────

    public CompletableFuture<ObservableList<Song>> getPlaylistItems(String playlistId) {
        return CompletableFuture.supplyAsync(() -> {
            if (quotaTracker.isExhausted()) return FXCollections.observableArrayList();
            ObservableList<Song> all = FXCollections.observableArrayList();
            String pageToken = null;

            do {
                String url = BASE_URL + "/playlistItems"
                    + "?part=snippet,contentDetails"
                    + "&maxResults=50"
                    + "&playlistId=" + playlistId
                    + "&key=" + apiKey
                    + (pageToken != null ? "&pageToken=" + pageToken : "");

                quotaTracker.record(YouTubeQuotaTracker.COST_LOOKUP);
                JsonObject root = JsonParser.parseString(rawGet(url)).getAsJsonObject();
                all.addAll(parsePlaylistItems(root));
                pageToken = root.has("nextPageToken") ? root.get("nextPageToken").getAsString() : null;

            } while (pageToken != null);

            // +1 unit per 50 songs to fetch durations
            if (!all.isEmpty())
                applyDurations(all, fetchDurations(all.stream().map(Song::getVideoId).collect(Collectors.toList())));

            return all;
        });
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private ObservableList<Song> parseVideoSearchResults(String json) {
        ObservableList<Song> songs = FXCollections.observableArrayList();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");

        for (JsonElement el : items) {
            JsonObject obj = el.getAsJsonObject();
            JsonObject id = obj.getAsJsonObject("id");
            if (!id.has("videoId")) continue;

            JsonObject snippet = obj.getAsJsonObject("snippet");
            String videoId = id.get("videoId").getAsString();
            String title   = snippet.get("title").getAsString();
            String channel = snippet.get("channelTitle").getAsString();
            String thumb   = thumbUrl(snippet);

            songs.add(new Song(videoId, title, channel, "—", thumb, channel));
        }
        return songs;
    }

    private List<YouTubePlaylistInfo> parsePlaylistSearchResults(String json) {
        List<YouTubePlaylistInfo> list = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");

        for (JsonElement el : items) {
            JsonObject obj = el.getAsJsonObject();
            JsonObject id = obj.getAsJsonObject("id");
            if (!id.has("playlistId")) continue;

            JsonObject snippet = obj.getAsJsonObject("snippet");
            String playlistId = id.get("playlistId").getAsString();
            String title      = snippet.get("title").getAsString();
            String channel    = snippet.get("channelTitle").getAsString();
            String thumb      = thumbUrl(snippet);
            String desc       = snippet.has("description") ? snippet.get("description").getAsString() : "";

            list.add(new YouTubePlaylistInfo(playlistId, title, channel, thumb, desc));
        }
        return list;
    }

    private Map<String, Integer> parseItemCounts(String json) {
        Map<String, Integer> map = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");

        for (JsonElement el : items) {
            JsonObject obj = el.getAsJsonObject();
            String id = obj.get("id").getAsString();
            int count = obj.getAsJsonObject("contentDetails")
                .get("itemCount").getAsInt();
            map.put(id, count);
        }
        return map;
    }

    private ObservableList<Song> parsePlaylistItems(JsonObject root) {
        ObservableList<Song> songs = FXCollections.observableArrayList();
        JsonArray items = root.getAsJsonArray("items");

        for (JsonElement el : items) {
            JsonObject obj     = el.getAsJsonObject();
            JsonObject snippet = obj.getAsJsonObject("snippet");
            if (!snippet.has("resourceId")) continue;

            JsonObject resourceId = snippet.getAsJsonObject("resourceId");
            if (!"youtube#video".equals(resourceId.get("kind").getAsString())) continue;

            String videoId = resourceId.get("videoId").getAsString();
            String title   = snippet.get("title").getAsString();
            String channel = snippet.get("channelTitle").getAsString();
            String thumb   = thumbUrl(snippet);

            songs.add(new Song(videoId, title, channel, "—", thumb, channel));
        }
        return songs;
    }

    // ── Fetch by ID (direct URL) ──────────────────────────────────────────────

    public CompletableFuture<Song> getVideoById(String videoId) {
        return CompletableFuture.supplyAsync(() -> {
            if (quotaTracker.isExhausted()) return null;
            String url = BASE_URL + "/videos"
                + "?part=snippet,contentDetails"
                + "&id=" + videoId
                + "&key=" + apiKey;
            quotaTracker.record(YouTubeQuotaTracker.COST_LOOKUP);
            String body = rawGet(url);
            JsonObject root  = JsonParser.parseString(body).getAsJsonObject();
            JsonArray  items = root.getAsJsonArray("items");
            if (items == null || items.size() == 0) return null;
            JsonObject obj     = items.get(0).getAsJsonObject();
            JsonObject snippet = obj.getAsJsonObject("snippet");
            String title    = snippet.get("title").getAsString();
            String channel  = snippet.get("channelTitle").getAsString();
            String thumb    = thumbUrl(snippet);
            String duration = parseDuration(
                obj.getAsJsonObject("contentDetails").get("duration").getAsString());
            return new Song(videoId, title, channel, duration, thumb, channel);
        });
    }

    public CompletableFuture<YouTubePlaylistInfo> getPlaylistById(String playlistId) {
        return CompletableFuture.supplyAsync(() -> {
            if (quotaTracker.isExhausted()) return null;
            String url = BASE_URL + "/playlists"
                + "?part=snippet,contentDetails"
                + "&id=" + playlistId
                + "&key=" + apiKey;
            quotaTracker.record(YouTubeQuotaTracker.COST_LOOKUP);
            String body  = rawGet(url);
            JsonObject root  = JsonParser.parseString(body).getAsJsonObject();
            JsonArray  items = root.getAsJsonArray("items");
            if (items == null || items.size() == 0) return null;
            JsonObject obj     = items.get(0).getAsJsonObject();
            JsonObject snippet = obj.getAsJsonObject("snippet");
            String title   = snippet.get("title").getAsString();
            String channel = snippet.get("channelTitle").getAsString();
            String thumb   = thumbUrl(snippet);
            String desc    = snippet.has("description") ? snippet.get("description").getAsString() : "";
            int count = 0;
            try { count = obj.getAsJsonObject("contentDetails").get("itemCount").getAsInt(); }
            catch (Exception ignored) {}
            YouTubePlaylistInfo info = new YouTubePlaylistInfo(playlistId, title, channel, thumb, desc);
            info.setItemCount(count);
            return info;
        });
    }

    // Batch-fetches durations for up to N video IDs; costs 1 quota unit per 50 IDs.
    private Map<String, String> fetchDurations(List<String> videoIds) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < videoIds.size(); i += 50) {
            List<String> batch = videoIds.subList(i, Math.min(i + 50, videoIds.size()));
            String url = BASE_URL + "/videos"
                + "?part=contentDetails"
                + "&id=" + String.join(",", batch)
                + "&key=" + apiKey;
            quotaTracker.record(YouTubeQuotaTracker.COST_LOOKUP);
            try {
                JsonObject root = JsonParser.parseString(rawGet(url)).getAsJsonObject();
                for (JsonElement el : root.getAsJsonArray("items")) {
                    JsonObject obj = el.getAsJsonObject();
                    String vid = obj.get("id").getAsString();
                    String dur = parseDuration(obj.getAsJsonObject("contentDetails").get("duration").getAsString());
                    result.put(vid, dur);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void applyDurations(Iterable<Song> songs, Map<String, String> durations) {
        songs.forEach(s -> { String d = durations.get(s.getVideoId()); if (d != null) s.setDuration(d); });
    }

    private String parseDuration(String iso) {
        if (iso == null || iso.isEmpty()) return "—";
        java.util.regex.Matcher m = DURATION_PATTERN.matcher(iso);
        if (!m.matches()) return "—";
        int h   = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int sec = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return h > 0 ? String.format("%d:%02d:%02d", h, min, sec)
                     : String.format("%d:%02d", min, sec);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    // Throws RuntimeException with the HTTP error body on non-2xx responses.
    private String rawGet(String url) {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : null;
            if (!resp.isSuccessful()) {
                String snippet = body != null ? body.substring(0, Math.min(300, body.length())) : "(empty)";
                throw new RuntimeException("HTTP " + resp.code() + ": " + snippet);
            }
            return body != null ? body : "{\"items\":[]}";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String thumbUrl(JsonObject snippet) {
        try {
            JsonObject thumbnails = snippet.getAsJsonObject("thumbnails");
            if (thumbnails.has("medium"))
                return thumbnails.getAsJsonObject("medium").get("url").getAsString();
            if (thumbnails.has("default"))
                return thumbnails.getAsJsonObject("default").get("url").getAsString();
        } catch (Exception ignored) {}
        return "";
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}