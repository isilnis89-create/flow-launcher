package com.nick.flowlauncher;

import android.content.ComponentName;
import android.graphics.Bitmap;

public final class AppEntry {
    public final String label;
    public final ComponentName component;
    public final Bitmap icon;
    public final char initial;

    public AppEntry(String label, ComponentName component, Bitmap icon) {
        this.label = label == null ? "" : label;
        this.component = component;
        this.icon = icon;
        String trimmed = this.label.trim();
        char c = trimmed.isEmpty() ? '#' : Character.toUpperCase(trimmed.charAt(0));
        this.initial = (c >= 'A' && c <= 'Z') ? c : '#';
    }

    public String key() {
        return component.flattenToShortString();
    }
}
