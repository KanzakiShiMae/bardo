package com.musicplayer.models;

public class YouTubePlaylistInfo {

    private final String playlistId;
    private final String title;
    private final String channelName;
    private final String thumbnailUrl;
    private final String description;
    private int itemCount;

    public YouTubePlaylistInfo(String playlistId, String title, String channelName,
                               String thumbnailUrl, String description) {
        this.playlistId = playlistId;
        this.title = title;
        this.channelName = channelName;
        this.thumbnailUrl = thumbnailUrl;
        this.description = description;
    }

    public String getPlaylistId() { return playlistId; }
    public String getTitle() { return title; }
    public String getChannelName() { return channelName; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getDescription() { return description; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
}