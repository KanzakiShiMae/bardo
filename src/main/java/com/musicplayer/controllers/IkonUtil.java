package com.musicplayer.controllers;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.javafx.StackedFontIcon;
import javafx.scene.paint.Color;

class IkonUtil {
    static final Color PRIMARY = Color.web("#5a4a6a");
    static final Color MUTED   = Color.web("#a090b0");

    static StackedFontIcon duotone(Ikon solid, Ikon regular, int size) {
        FontIcon bg = new FontIcon(solid); bg.setIconSize(size); bg.setIconColor(MUTED);
        FontIcon fg = new FontIcon(regular); fg.setIconSize(size); fg.setIconColor(PRIMARY);
        StackedFontIcon sfi = new StackedFontIcon();
        sfi.getChildren().addAll(bg, fg);
        sfi.setIconSize(size);
        return sfi;
    }
}
