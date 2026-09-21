package com.nick.flowlauncher;

public final class FlowNotificationInfo {
    public final int count;
    public final String title;
    public final String text;

    public FlowNotificationInfo(int count, String title, String text) {
        this.count = count;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
    }
}
