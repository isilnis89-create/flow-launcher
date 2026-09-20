package com.nick.flowlauncher;

import android.graphics.Bitmap;

public final class WebShortcut {
    public final String id;
    public final String name;
    public final String url;
    public final Bitmap icon;

    public WebShortcut(String id, String name, String url, Bitmap icon) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.icon = icon;
    }

    public String key() {
        return "web:" + id;
    }
}
