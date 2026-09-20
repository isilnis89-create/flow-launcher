package com.nick.flowlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LauncherView extends View {
    public interface Callback {
        void launchApp(AppEntry app);
        void openWebShortcut(WebShortcut shortcut);
        void requestAddShortcut();
        void toggleFavorite(AppEntry app);
        void removeShortcut(WebShortcut shortcut);
        void requestSearch();
        void requestLauncherSettings();
    }

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int HOME = 0, LETTER = 1, SEARCH = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator ease = new PathInterpolator(.16f, 1f, .3f, 1f);

    private final List<AppEntry> apps = new ArrayList<>();
    private final List<WebShortcut> shortcuts = new ArrayList<>();
    private final List<String> favoriteKeys = new ArrayList<>();
    private final List<FavoriteItem> favorites = new ArrayList<>();
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private final Map<String, AppEntry> appIndex = new HashMap<>();
    private final Map<String, WebShortcut> shortcutIndex = new HashMap<>();

    private Callback callback;
    private final float density;
    private int mode = HOME;
    private char activeLetter = 'A';
    private String query = "";
    private float overlay = 0f;
    private ValueAnimator animator;
    private int pressed = -1;
    private boolean homePressed;
    private boolean scrubbing;
    private boolean longPressed;
    private float downX, downY;
    private final Runnable longPressRunnable = this::fireLongPress;

    public LauncherView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                invalidate();
                handler.postDelayed(this, 30000);
            }
        }, 30000);
    }

    public void setCallback(Callback callback) { this.callback = callback; }

    public void setData(List<AppEntry> newApps, List<WebShortcut> newShortcuts, List<String> keys) {
        apps.clear();
        shortcuts.clear();
        favoriteKeys.clear();
        if (newApps != null) apps.addAll(newApps);
        if (newShortcuts != null) shortcuts.addAll(newShortcuts);
        if (keys != null) favoriteKeys.addAll(keys);
        rebuild();
        refreshVisible();
        invalidate();
    }

    public void setSearchMode(boolean enabled) {
        mode = enabled ? SEARCH : HOME;
        if (!enabled) query = "";
        refreshVisible();
        animateOverlay(enabled);
    }

    public void setSearchQuery(String value) {
        query = value == null ? "" : value.trim();
        mode = SEARCH;
        refreshVisible();
        invalidate();
    }

    public boolean closeOverlay() {
        if (mode == HOME) return false;
        mode = HOME;
        query = "";
        visibleApps.clear();
        animateOverlay(false);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x18000000);
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (overlay > 0f) {
            paint.setColor(alpha(Color.BLACK, (int)(228 * overlay)));
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }

        drawClock(c);
        float homeAlpha = Math.max(0f, 1f - 1.35f * overlay);
        drawFavorites(c, homeAlpha);
        drawBottom(c, homeAlpha);
        drawAlphabet(c);
        if (overlay > .001f && mode != HOME) drawApps(c);
    }

    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.getTime());
        String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime());
        text.setTypeface(android.graphics.Typeface.create("sans-serif-thin", 0));
        text.setTextSize(sp(39));
        text.setColor(Color.WHITE);
        text.setShadowLayer(dp(7), 0, dp(2), 0x66000000);
        c.drawText(time, dp(26), dp(74), text);
        text.setTextSize(sp(15));
        text.setColor(0xE6FFFFFF);
        c.drawText(date, dp(28), dp(99), text);
        text.clearShadowLayer();
    }

    private void drawFavorites(Canvas c, float a) {
        float start = homeStart();
        int max = Math.min(favorites.size(), maxHomeRows());
        for (int i = 0; i < max; i++) {
            FavoriteItem item = favorites.get(i);
            float cy = start + i * rowHeight() - dp(18) * overlay;
            float scale = homePressed && pressed == i ? .94f : 1f;
            float iconSize = dp(45) * scale;
            float x = dp(27) + (dp(45) - iconSize) / 2f;
            float y = cy - iconSize / 2f;
            Bitmap icon = item.app != null ? item.app.icon : item.shortcut.icon;
            paint.setAlpha(clamp((int)(255 * a)));
            if (icon != null) {
                rect.set(x, y, x + iconSize, y + iconSize);
                c.drawBitmap(icon, null, rect, paint);
            } else {
                drawGlobe(c, x, y, iconSize, a);
            }
            paint.setAlpha(255);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
            text.setTextSize(sp(20));
            text.setColor(alpha(Color.WHITE, clamp((int)(245 * a))));
            String label = item.app != null ? item.app.label : item.shortcut.name;
            c.drawText(shorten(label, 23), dp(87), cy + dp(7), text);
        }
    }

    private void drawBottom(Canvas c, float a) {
        float y = getHeight() - dp(74);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextSize(sp(15));
        text.setColor(alpha(Color.WHITE, clamp((int)(215 * a))));
        text.setTextSize(sp(13));
        text.setColor(alpha(Color.WHITE, clamp((int)(150 * a))));
        c.drawText("↑ search", dp(28), y, text);
        String add = "+ website";
        c.drawText(add, getWidth() - dp(30) - text.measureText(add), y, text);
    }

    private void drawAlphabet(Canvas c) {
        float top = alphabetTop(), bottom = alphabetBottom();
        float step = (bottom - top) / 25f;
        float x = getWidth() - dp(16);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 26; i++) {
            char ch = LETTERS.charAt(i);
            boolean on = mode == LETTER && activeLetter == ch;
            text.setTextSize(sp(on ? 12 : 9));
            text.setColor(on ? Color.WHITE : 0xBFFFFFFF);
            c.drawText(String.valueOf(ch), x, top + i * step + dp(3), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        if (mode == LETTER && overlay > 0) {
            float bx = getWidth() - dp(66), by = yForLetter(activeLetter);
            paint.setColor(alpha(Color.BLACK, (int)(150 * overlay)));
            c.drawCircle(bx, by, dp(26), paint);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            text.setTextSize(sp(23));
            text.setColor(alpha(Color.WHITE, (int)(255 * overlay)));
            c.drawText(String.valueOf(activeLetter), bx, by + dp(8), text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawApps(Canvas c) {
        float start = dp(156), row = overlayRowHeight();
        int max = Math.min(visibleApps.size(), maxOverlayRows());
        if (mode == SEARCH) {
            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
            text.setTextSize(sp(14));
            text.setColor(alpha(Color.WHITE, (int)(185 * overlay)));
            c.drawText(query.isEmpty() ? "All apps" : "Results for “" + shorten(query, 22) + "”", dp(28), dp(133), text);
        }
        if (max == 0) {
            text.setTextSize(sp(18));
            text.setColor(alpha(Color.WHITE, (int)(215 * overlay)));
            c.drawText(mode == SEARCH ? "No matching apps" : "No apps under " + activeLetter, dp(28), start + dp(28), text);
            return;
        }
        for (int i = 0; i < max; i++) {
            AppEntry app = visibleApps.get(i);
            float p = Math.max(0f, Math.min(1f, overlay * 1.16f - i * .045f));
            p = ease.getInterpolation(p);
            float cy = start + i * row;
            float slide = dp(34) * (1f - p);
            float scale = !homePressed && pressed == i ? .94f : .90f + .10f * p;
            float iconSize = dp(46) * scale;
            if (!homePressed && pressed == i) {
                paint.setColor(0x16FFFFFF);
                rect.set(dp(18), cy - dp(29), getWidth() - dp(52), cy + dp(29));
                c.drawRoundRect(rect, dp(18), dp(18), paint);
            }
            if (app.icon != null) {
                paint.setAlpha(clamp((int)(255 * p)));
                rect.set(dp(29) + slide, cy - iconSize / 2f, dp(29) + slide + iconSize, cy + iconSize / 2f);
                c.drawBitmap(app.icon, null, rect, paint);
                paint.setAlpha(255);
            }
            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
            text.setTextSize(sp(20));
            text.setColor(alpha(Color.WHITE, clamp((int)(250 * p))));
            c.drawText(shorten(app.label, 24), dp(89) + slide, cy + dp(7), text);
            if (favoriteKeys.contains(app.key())) {
                text.setTextAlign(Paint.Align.RIGHT);
                text.setTextSize(sp(16));
                text.setColor(alpha(Color.WHITE, clamp((int)(160 * p))));
                c.drawText("•", getWidth() - dp(58), cy + dp(6), text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void drawGlobe(Canvas c, float left, float top, float size, float a) {
        float cx = left + size / 2f, cy = top + size / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(Color.WHITE, clamp((int)(220 * a))));
        c.drawCircle(cx, cy, size / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(alpha(Color.BLACK, clamp((int)(145 * a))));
        c.drawCircle(cx, cy, size * .29f, paint);
        c.drawLine(left + size * .18f, cy, left + size * .82f, cy, paint);
        c.drawLine(cx, top + size * .18f, cx, top + size * .82f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; longPressed = false;
                if (inAlphabet(x, y)) {
                    scrubbing = true; pressed = -1; selectLetter(y, true);
                } else {
                    scrubbing = false;
                    findPressed(y);
                    // When the app/letter overlay is open, tapping empty space dismisses it.
                    if (mode != HOME && pressed < 0) {
                        closeOverlay();
                        return true;
                    }
                    if (pressed >= 0) handler.postDelayed(longPressRunnable, 520);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scrubbing) { selectLetter(y, false); return true; }
                if (distance(downX, downY, x, y) > dp(12)) {
                    handler.removeCallbacks(longPressRunnable);
                    if (downY - y > dp(74) && mode == HOME && callback != null && !longPressed) {
                        longPressed = true; pressed = -1; callback.requestSearch();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (scrubbing) { scrubbing = false; return true; }
                if (!longPressed && distance(downX, downY, x, y) < dp(18)) {
                    if (handleBottom(x, y)) { clearPress(); return true; }
                    activate();
                }
                clearPress();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                scrubbing = false; clearPress(); return true;
        }
        return true;
    }

    private void findPressed(float y) {
        pressed = -1;
        if (mode == HOME) {
            int i = Math.round((y - homeStart()) / rowHeight());
            if (i >= 0 && i < Math.min(favorites.size(), maxHomeRows())) {
                float cy = homeStart() + i * rowHeight();
                if (Math.abs(y - cy) < rowHeight() * .48f) { pressed = i; homePressed = true; }
            }
        } else {
            int i = Math.round((y - dp(156)) / overlayRowHeight());
            if (i >= 0 && i < Math.min(visibleApps.size(), maxOverlayRows())) {
                float cy = dp(156) + i * overlayRowHeight();
                if (Math.abs(y - cy) < overlayRowHeight() * .48f) { pressed = i; homePressed = false; }
            }
        }
    }

    private void activate() {
        if (pressed < 0 || callback == null) return;
        if (homePressed) {
            if (pressed >= favorites.size()) return;
            FavoriteItem item = favorites.get(pressed);
            if (item.app != null) callback.launchApp(item.app);
            else callback.openWebShortcut(item.shortcut);
        } else if (pressed < visibleApps.size()) callback.launchApp(visibleApps.get(pressed));
    }

    private void fireLongPress() {
        if (pressed < 0 || callback == null) return;
        longPressed = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (homePressed) {
            if (pressed >= favorites.size()) return;
            FavoriteItem item = favorites.get(pressed);
            if (item.app != null) callback.toggleFavorite(item.app);
            else callback.removeShortcut(item.shortcut);
        } else if (pressed < visibleApps.size()) callback.toggleFavorite(visibleApps.get(pressed));
        clearPress();
    }

    private boolean handleBottom(float x, float y) {
        if (mode != HOME || y < getHeight() - dp(115) || callback == null) return false;
        if (x < getWidth() * .52f) callback.requestSearch(); else callback.requestAddShortcut();
        return true;
    }

    private void selectLetter(float y, boolean initial) {
        char old = activeLetter;
        float t = Math.max(0f, Math.min(1f, (y - alphabetTop()) / Math.max(1f, alphabetBottom() - alphabetTop())));
        activeLetter = LETTERS.charAt(Math.max(0, Math.min(25, Math.round(t * 25f))));
        mode = LETTER;
        refreshVisible();
        if (initial) animateOverlay(true);
        if (old != activeLetter || initial) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }

    private void animateOverlay(boolean show) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(overlay, show ? 1f : 0f);
        animator.setDuration(show ? 230 : 180);
        animator.setInterpolator(ease);
        animator.addUpdateListener(a -> { overlay = (float)a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    private void refreshVisible() {
        visibleApps.clear();
        if (mode == LETTER) {
            for (AppEntry app : apps) if (app.initial == activeLetter) visibleApps.add(app);
        } else if (mode == SEARCH) {
            String q = query.toLowerCase(Locale.ROOT);
            for (AppEntry app : apps) if (q.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(q)) visibleApps.add(app);
        }
    }

    private void rebuild() {
        appIndex.clear(); shortcutIndex.clear(); favorites.clear();
        for (AppEntry a : apps) appIndex.put(a.key(), a);
        for (WebShortcut s : shortcuts) shortcutIndex.put(s.key(), s);
        for (String key : favoriteKeys) {
            if (key.startsWith("web:")) {
                WebShortcut s = shortcutIndex.get(key);
                if (s != null) favorites.add(new FavoriteItem(s));
            } else {
                AppEntry a = appIndex.get(key);
                if (a != null) favorites.add(new FavoriteItem(a));
            }
        }
    }

    private void clearPress() { pressed = -1; homePressed = false; invalidate(); }
    private boolean inAlphabet(float x, float y) { return x > getWidth() - dp(52) && y >= alphabetTop() - dp(20) && y <= alphabetBottom() + dp(20); }
    private float alphabetTop() { return dp(124); }
    private float alphabetBottom() { return Math.max(dp(400), getHeight() - dp(128)); }
    private float yForLetter(char c) { return alphabetTop() + (alphabetBottom() - alphabetTop()) * Math.max(0, LETTERS.indexOf(c)) / 25f; }
    private float homeStart() { return Math.max(dp(225), getHeight() * .36f); }
    private float rowHeight() { return dp(61); }
    private float overlayRowHeight() { return dp(64); }
    private int maxHomeRows() { return Math.max(3, (int)((getHeight() - homeStart() - dp(105)) / rowHeight())); }
    private int maxOverlayRows() { return Math.max(4, (int)((getHeight() - dp(246)) / overlayRowHeight())); }
    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
    private int alpha(int color, int a) { return (clamp(a) << 24) | (color & 0x00FFFFFF); }
    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    private float distance(float x1,float y1,float x2,float y2){ float dx=x2-x1,dy=y2-y1; return (float)Math.sqrt(dx*dx+dy*dy); }
    private String shorten(String s,int n){ if(s==null)return ""; return s.length()<=n?s:s.substring(0,Math.max(1,n-1))+"…"; }

    private static final class FavoriteItem {
        final AppEntry app; final WebShortcut shortcut;
        FavoriteItem(AppEntry a){ app=a; shortcut=null; }
        FavoriteItem(WebShortcut s){ app=null; shortcut=s; }
    }
}
