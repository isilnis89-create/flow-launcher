package com.nick.flowlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
        void editFavorite(AppEntry app);
        void removeShortcut(WebShortcut shortcut);
        void requestSearch();
        void requestPhone();
        void requestCamera();
        void requestClock();
        void reorderFavorite(String key, int targetIndex);
        void requestNotificationPeek(String packageName);
        void requestFlowSettings();
        void requestTidalAction(int action);
        void openTidal();
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
    private final Map<String, FavoriteOverride> favoriteOverrides = new HashMap<>();
    private final Map<String, FlowNotificationInfo> notifications = new HashMap<>();

    private Callback callback;
    private final float density;
    private int mode = HOME;
    private char activeLetter = 'A';
    private String query = "";
    private float overlay = 0f;
    private ValueAnimator animator;
    private ValueAnimator ambientAnimator;
    private ValueAnimator letterAnimator;
    private int pressed = -1;
    private boolean homePressed;
    private boolean scrubbing;
    private boolean longPressed;
    private float downX, downY;
    private float drawerAnchorY = -1f;
    private int accentColor = Color.rgb(108, 220, 214);
    private boolean draggingFavorite;
    private int dragStartIndex = -1;
    private int dragTargetIndex = -1;
    private float dragY = -1f;
    private boolean dragMoved;
    private String dragFavoriteKey;
    private TidalMediaState tidalState = TidalMediaState.empty();
    private boolean ambientEnabled = true;
    private float ambient = 0f;
    private int favoriteDensity = 15;
    private float letterTransition = 1f;
    private boolean emptyLongPressArmed;
    private final Runnable longPressRunnable = this::fireLongPress;
    private final Runnable emptyLongPressRunnable = this::fireEmptyLongPress;
    private final Runnable ambientRunnable = this::enterAmbient;

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
        scheduleAmbient();
    }

    public void setCallback(Callback callback) { this.callback = callback; }

    public void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    public void setNotificationState(Map<String, FlowNotificationInfo> state, TidalMediaState tidal) {
        notifications.clear();
        if (state != null) notifications.putAll(state);
        tidalState = tidal == null ? TidalMediaState.empty() : tidal;
        invalidate();
    }

    public void setAmbientEnabled(boolean enabled) {
        ambientEnabled = enabled;
        if (!enabled) {
            handler.removeCallbacks(ambientRunnable);
            animateAmbient(0f);
        } else {
            wakeAmbient();
        }
    }

    public void setFavoriteDensity(int density) {
        favoriteDensity = Math.max(10, Math.min(18, density));
        invalidate();
    }

    public void setData(List<AppEntry> newApps, List<WebShortcut> newShortcuts,
                        List<String> keys, Map<String, FavoriteOverride> overrides) {
        apps.clear();
        shortcuts.clear();
        favoriteKeys.clear();
        favoriteOverrides.clear();
        if (newApps != null) apps.addAll(newApps);
        if (newShortcuts != null) shortcuts.addAll(newShortcuts);
        if (keys != null) favoriteKeys.addAll(keys);
        if (overrides != null) favoriteOverrides.putAll(overrides);
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
        drawerAnchorY = -1f;
        visibleApps.clear();
        animateOverlay(false);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        if (ambientAnimator != null) ambientAnimator.cancel();
        if (letterAnimator != null) letterAnimator.cancel();
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

        float homeAlpha = Math.max(0f, 1f - 1.35f * overlay);
        float favoriteAlpha = homeAlpha * (1f - .30f * ambient);
        float dockAlpha = homeAlpha * (1f - .88f * ambient);

        c.save();
        float homeScale = 1f - .018f * overlay;
        c.scale(homeScale, homeScale, getWidth() * .50f, getHeight() * .48f);
        drawClock(c, homeAlpha);
        drawFavorites(c, favoriteAlpha);
        if (tidalState != null && tidalState.active) drawTidal(c, homeAlpha * (1f - .35f * ambient));
        drawBottom(c, dockAlpha);
        c.restore();

        drawAlphabet(c);
        if (overlay > .001f && mode != HOME) drawApps(c);
    }

    private void drawClock(Canvas c, float a) {
        Calendar now = Calendar.getInstance();
        String hh = new SimpleDateFormat("HH", Locale.getDefault()).format(now.getTime());
        String mm = new SimpleDateFormat("mm", Locale.getDefault()).format(now.getTime());
        String day = new SimpleDateFormat("EEE", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault());
        String date = new SimpleDateFormat("dd MMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault());

        float alpha = Math.max(0f, Math.min(1f, a));
        float left = dp(25);
        float top = dp(43);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(accentColor, clamp((int)(220 * alpha))));
        c.drawRect(left, top, left + dp(2.5f), top + dp(65), paint);

        text.setTypeface(android.graphics.Typeface.create("sans-serif-thin", 0));
        text.setTextSize(sp(42));
        text.setColor(alpha(Color.WHITE, clamp((int)(250 * alpha))));
        text.setShadowLayer(dp(5), 0, dp(2), 0x55000000);
        c.drawText(hh, left + dp(14), dp(82), text);

        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextSize(sp(18));
        text.setColor(alpha(accentColor, clamp((int)(240 * alpha))));
        c.drawText(":", left + dp(67), dp(79), text);

        text.setTypeface(android.graphics.Typeface.create("sans-serif-thin", 0));
        text.setTextSize(sp(42));
        text.setColor(alpha(Color.WHITE, clamp((int)(250 * alpha))));
        c.drawText(mm, left + dp(79), dp(82), text);
        text.clearShadowLayer();

        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextSize(sp(10.5f));
        text.setColor(alpha(Color.WHITE, clamp((int)(165 * alpha))));
        c.drawText("LOCAL / " + day + " / " + date, left + dp(15), dp(105), text);

        paint.setColor(alpha(Color.WHITE, clamp((int)(52 * alpha))));
        c.drawRect(left + dp(15), dp(112), left + dp(145), dp(113), paint);
        paint.setColor(alpha(accentColor, clamp((int)(185 * alpha))));
        c.drawRect(left + dp(15), dp(112), left + dp(48), dp(113), paint);
    }

    private void drawFavorites(Canvas c, float a) {
        float start = homeStart();
        float row = homeRowHeight();
        int max = Math.min(favorites.size(), maxHomeRows());

        if (draggingFavorite && dragTargetIndex >= 0) {
            float markerY = start + dragTargetIndex * row;
            paint.setColor(alpha(accentColor, clamp((int)(135 * a))));
            c.drawRoundRect(new RectF(dp(74), markerY - dp(1), getWidth() - dp(78), markerY + dp(1)),
                    dp(1), dp(1), paint);
        }

        for (int i = 0; i < max; i++) {
            if (draggingFavorite && i == dragStartIndex) continue;
            FavoriteItem item = favorites.get(i);
            float cy = start + i * row - dp(10) * overlay;
            drawFavoriteItem(c, item, i, cy, a, 1f);
        }

        if (draggingFavorite && dragStartIndex >= 0 && dragStartIndex < favorites.size()) {
            FavoriteItem item = favorites.get(dragStartIndex);
            float liftScale = 1.07f;
            paint.setColor(alpha(Color.BLACK, clamp((int)(75 * a))));
            rect.set(dp(18), dragY - row * .50f, getWidth() - dp(70), dragY + row * .50f);
            c.drawRoundRect(rect, dp(16), dp(16), paint);
            drawFavoriteItem(c, item, dragStartIndex, dragY, a, liftScale);
        }
    }

    private void drawFavoriteItem(Canvas c, FavoriteItem item, int index, float cy, float a, float extraScale) {
        float row = homeRowHeight();
        float pressScale = homePressed && pressed == index && !draggingFavorite ? .94f : 1f;
        float scale = pressScale * extraScale;

        float iconSize = Math.min(dp(36), row * .76f) * scale;
        float slot = dp(38);
        float x = dp(24) + (slot - iconSize) / 2f;
        float y = cy - iconSize / 2f;

        FavoriteOverride favoriteOverride = item.app != null ? favoriteOverrides.get(item.app.key()) : null;
        Bitmap icon = item.app != null
                ? (favoriteOverride != null && favoriteOverride.icon != null ? favoriteOverride.icon : item.app.icon)
                : item.shortcut.icon;

        paint.setAlpha(clamp((int)(255 * a)));
        if (icon != null) {
            rect.set(x, y, x + iconSize, y + iconSize);
            c.drawBitmap(icon, null, rect, paint);
        } else {
            drawGlobe(c, x, y, iconSize, a);
        }
        paint.setAlpha(255);

        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextSize(sp(row < dp(36) ? 15.5f : 17.5f));
        text.setColor(alpha(Color.WHITE, clamp((int)(246 * a))));

        String label;
        if (item.app != null) {
            label = favoriteOverride != null && favoriteOverride.name != null && !favoriteOverride.name.isEmpty()
                    ? favoriteOverride.name : item.app.label;
        } else {
            label = item.shortcut.name;
        }
        c.drawText(shorten(label, 25), dp(75), cy + dp(5.5f), text);
    }

    private void drawBottom(Canvas c, float a) {
        float top = getHeight() - dp(80);
        float bottom = getHeight() - dp(22);
        float left = dp(20);
        float right = getWidth() - dp(20);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(Color.BLACK, clamp((int)(150 * a))));
        rect.set(left, top, right, bottom);
        c.drawRoundRect(rect, dp(25), dp(25), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(alpha(accentColor, clamp((int)(65 * a))));
        c.drawRoundRect(rect, dp(25), dp(25), paint);
        paint.setStyle(Paint.Style.FILL);

        float width = right - left;
        float cy = (top + bottom) / 2f;
        float xPhone = left + width * .125f;
        float xSearch = left + width * .375f;
        float xWeb = left + width * .625f;
        float xCamera = left + width * .875f;

        drawDockGlyph(c, xPhone, cy, 0, a);
        drawDockGlyph(c, xSearch, cy, 1, a);
        drawDockGlyph(c, xWeb, cy, 2, a);
        drawDockGlyph(c, xCamera, cy, 3, a);
    }

    private void drawDockGlyph(Canvas c, float cx, float cy, int type, float a) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(alpha(Color.WHITE, clamp((int)(230 * a))));

        if (type == 0) {
            Path p = new Path();
            p.moveTo(cx - dp(8), cy - dp(10));
            p.cubicTo(cx - dp(11), cy - dp(6), cx - dp(7), cy + dp(4), cx, cy + dp(9));
            p.cubicTo(cx + dp(6), cy + dp(13), cx + dp(10), cy + dp(10), cx + dp(10), cy + dp(6));
            p.lineTo(cx + dp(5), cy + dp(2));
            p.cubicTo(cx + dp(3), cy + dp(1), cx + dp(2), cy + dp(4), cx, cy + dp(3));
            p.cubicTo(cx - dp(3), cy + dp(1), cx - dp(5), cy - dp(2), cx - dp(5), cy - dp(4));
            p.cubicTo(cx - dp(5), cy - dp(6), cx - dp(2), cy - dp(6), cx - dp(3), cy - dp(8));
            p.close();
            c.drawPath(p, paint);
        } else if (type == 1) {
            c.drawCircle(cx - dp(2), cy - dp(2), dp(7), paint);
            c.drawLine(cx + dp(4), cy + dp(4), cx + dp(10), cy + dp(10), paint);
        } else if (type == 2) {
            c.drawCircle(cx, cy, dp(8), paint);
            c.drawLine(cx - dp(8), cy, cx + dp(8), cy, paint);
            c.drawOval(new RectF(cx - dp(4), cy - dp(8), cx + dp(4), cy + dp(8)), paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + dp(9), cy - dp(8), dp(2.5f), paint);
            paint.setStyle(Paint.Style.STROKE);
        } else {
            rect.set(cx - dp(9), cy - dp(7), cx + dp(9), cy + dp(8));
            c.drawRoundRect(rect, dp(3), dp(3), paint);
            c.drawCircle(cx, cy + dp(.5f), dp(4), paint);
            c.drawLine(cx - dp(4), cy - dp(9), cx + dp(3), cy - dp(9), paint);
        }

        paint.setStyle(Paint.Style.FILL);
    }

    private void drawAlphabet(Canvas c) {
        float top = alphabetTop(), bottom = alphabetBottom();
        float step = (bottom - top) / 25f;
        float baseX = getWidth() - dp(15);
        int activeIndex = Math.max(0, LETTERS.indexOf(activeLetter));

        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 26; i++) {
            char ch = LETTERS.charAt(i);
            boolean letterMode = mode == LETTER;
            boolean hasApps = hasAppsForLetter(ch);
            float distance = Math.abs(i - activeIndex);
            float wave = letterMode ? Math.max(0f, 1f - distance / 4.0f) * overlay : 0f;
            boolean on = letterMode && i == activeIndex;

            float letterX = baseX - dp(24) * wave;
            float size = 8.5f + 5.5f * wave;

            int idleAlpha = hasApps ? 92 : 38;
            int alphaValue = on ? 255 : (letterMode ? (int)(112 + 92 * wave) : idleAlpha);

            text.setTextSize(sp(size));
            text.setColor(on ? alpha(accentColor, 255) : alpha(Color.WHITE, alphaValue));
            c.drawText(String.valueOf(ch), letterX, top + i * step + dp(3), text);
        }

        text.setTextAlign(Paint.Align.LEFT);
        if (mode == LETTER && overlay > 0f) {
            float bubbleY = drawerAnchorY > 0f ? drawerAnchorY : yForLetter(activeLetter);
            bubbleY = Math.max(dp(118), Math.min(getHeight() - dp(112), bubbleY));
            float bubbleX = getWidth() - dp(76);

            paint.setColor(alpha(Color.BLACK, (int)(225 * overlay)));
            c.drawCircle(bubbleX, bubbleY, dp(29), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            paint.setColor(alpha(accentColor, (int)(210 * overlay)));
            c.drawCircle(bubbleX, bubbleY, dp(29), paint);
            paint.setStyle(Paint.Style.FILL);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
            text.setTextSize(sp(24));
            text.setColor(alpha(accentColor, (int)(255 * overlay)));
            c.drawText(String.valueOf(activeLetter), bubbleX, bubbleY + dp(8), text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private boolean hasAppsForLetter(char letter) {
        for (AppEntry app : apps) if (app.initial == letter) return true;
        return false;
    }

    private void drawApps(Canvas c) {
        if (mode == LETTER) {
            drawLetterApps(c);
        } else {
            drawSearchApps(c);
        }
    }

    private void drawLetterApps(Canvas c) {
        int max = Math.min(visibleApps.size(), Math.min(8, maxOverlayRows()));
        if (max == 0) {
            float y = drawerAnchorY > 0f ? drawerAnchorY : getHeight() * .48f;
            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
            text.setTextSize(sp(18));
            text.setColor(alpha(Color.WHITE, (int)(215 * overlay)));
            c.drawText("No apps under " + activeLetter, dp(30), y, text);
            return;
        }

        float row = dp(59);
        float anchor = drawerAnchorY > 0f ? drawerAnchorY : getHeight() * .48f;
        anchor = Math.max(dp(180), Math.min(getHeight() - dp(180), anchor));

        float total = (max - 1) * row;
        float start = anchor - total / 2f;
        float minStart = dp(155);
        float maxStart = getHeight() - dp(135) - total;
        if (maxStart < minStart) maxStart = minStart;
        start = Math.max(minStart, Math.min(maxStart, start));

        for (int i = 0; i < max; i++) {
            AppEntry app = visibleApps.get(i);
            float cy = start + i * row;
            float distance = Math.abs(cy - anchor);
            float proximity = Math.max(0f, 1f - distance / (row * 3.2f));
            float entrance = ease.getInterpolation(Math.max(0f, Math.min(1f, overlay * 1.18f - i * .025f)));

            float itemAlpha = entrance * (.84f + .16f * proximity);
            float scale = .93f + .07f * proximity;
            float pull = dp(24) * proximity;
            float iconSize = dp(46) * scale;
            float iconX = dp(28) + pull;
            float labelX = dp(89) + pull;

            if (!homePressed && pressed == i) {
                paint.setColor(alpha(accentColor, (int)(42 * itemAlpha)));
                rect.set(dp(18) + pull * .25f, cy - dp(27), getWidth() - dp(70), cy + dp(27));
                c.drawRoundRect(rect, dp(17), dp(17), paint);
            }

            if (app.icon != null) {
                paint.setAlpha(clamp((int)(255 * itemAlpha)));
                rect.set(iconX, cy - iconSize / 2f, iconX + iconSize, cy + iconSize / 2f);
                c.drawBitmap(app.icon, null, rect, paint);
                paint.setAlpha(255);
            }

            text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
            text.setTextSize(sp(18.5f + 2.5f * proximity));
            text.setColor(alpha(Color.WHITE, clamp((int)(250 * itemAlpha))));
            c.drawText(shorten(app.label, 24), labelX, cy + dp(7), text);

            if (favoriteKeys.contains(app.key())) {
                text.setTextAlign(Paint.Align.RIGHT);
                text.setTextSize(sp(15));
                text.setColor(alpha(accentColor, clamp((int)(190 * itemAlpha))));
                c.drawText("•", getWidth() - dp(72), cy + dp(5), text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void drawSearchApps(Canvas c) {
        float start = dp(156), row = overlayRowHeight();
        int max = Math.min(visibleApps.size(), maxOverlayRows());

        text.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", 0));
        text.setTextSize(sp(14));
        text.setColor(alpha(Color.WHITE, (int)(185 * overlay)));
        c.drawText(query.isEmpty() ? "All apps" : "Results for “" + shorten(query, 22) + "”", dp(28), dp(133), text);

        if (max == 0) {
            text.setTextSize(sp(18));
            text.setColor(alpha(Color.WHITE, (int)(215 * overlay)));
            c.drawText("No matching apps", dp(28), start + dp(28), text);
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
                downX = x;
                downY = y;
                longPressed = false;
                dragMoved = false;

                if (inAlphabet(x, y)) {
                    scrubbing = true;
                    pressed = -1;
                    selectLetter(y, true);
                } else {
                    scrubbing = false;
                    findPressed(y);

                    if (mode != HOME && pressed < 0) {
                        closeOverlay();
                        return true;
                    }

                    if (pressed >= 0) handler.postDelayed(longPressRunnable, 520);
                }

                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scrubbing) {
                    selectLetter(y, false);
                    return true;
                }

                if (draggingFavorite) {
                    dragY = Math.max(homeStart(), Math.min(getHeight() - dp(92), y));
                    float row = homeRowHeight();
                    int visibleCount = Math.min(favorites.size(), maxHomeRows());
                    int target = Math.round((dragY - homeStart()) / row);
                    target = Math.max(0, Math.min(Math.max(0, visibleCount - 1), target));
                    if (target != dragTargetIndex) {
                        dragTargetIndex = target;
                        dragMoved = dragTargetIndex != dragStartIndex;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                    return true;
                }

                if (distance(downX, downY, x, y) > dp(12)) {
                    handler.removeCallbacks(longPressRunnable);

                    if (downY - y > dp(74) && mode == HOME && callback != null && !longPressed) {
                        longPressed = true;
                        pressed = -1;
                        callback.requestSearch();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);

                if (scrubbing) {
                    scrubbing = false;
                    return true;
                }

                if (draggingFavorite) {
                    finishFavoriteDrag();
                    return true;
                }

                if (!longPressed && distance(downX, downY, x, y) < dp(18)) {
                    if (mode == HOME && inClock(x, y) && callback != null) {
                        callback.requestClock();
                        clearPress();
                        return true;
                    }

                    if (handleBottom(x, y)) {
                        clearPress();
                        return true;
                    }

                    activate();
                }

                clearPress();
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                scrubbing = false;
                cancelFavoriteDrag();
                clearPress();
                return true;
        }

        return true;
    }

    private void findPressed(float y) {
        pressed = -1;
        if (mode == HOME) {
            float row = homeRowHeight();
            int i = Math.round((y - homeStart()) / row);
            if (i >= 0 && i < Math.min(favorites.size(), maxHomeRows())) {
                float cy = homeStart() + i * row;
                if (Math.abs(y - cy) < row * .48f) { pressed = i; homePressed = true; }
            }
        } else if (mode == LETTER) {
            int max = Math.min(visibleApps.size(), Math.min(8, maxOverlayRows()));
            if (max <= 0) return;
            float row = dp(59);
            float anchor = drawerAnchorY > 0f ? drawerAnchorY : getHeight() * .48f;
            anchor = Math.max(dp(180), Math.min(getHeight() - dp(180), anchor));
            float total = (max - 1) * row;
            float start = anchor - total / 2f;
            float minStart = dp(155);
            float maxStart = getHeight() - dp(135) - total;
            if (maxStart < minStart) maxStart = minStart;
            start = Math.max(minStart, Math.min(maxStart, start));
            int i = Math.round((y - start) / row);
            if (i >= 0 && i < max) {
                float cy = start + i * row;
                if (Math.abs(y - cy) < row * .48f) { pressed = i; homePressed = false; }
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

            draggingFavorite = true;
            dragStartIndex = pressed;
            dragTargetIndex = pressed;
            dragY = homeStart() + pressed * homeRowHeight();
            dragMoved = false;
            dragFavoriteKey = favorites.get(pressed).key();
            invalidate();
            return;
        }

        if (pressed < visibleApps.size()) {
            callback.toggleFavorite(visibleApps.get(pressed));
            clearPress();
        }
    }

    private void finishFavoriteDrag() {
        if (!draggingFavorite || dragStartIndex < 0 || dragStartIndex >= favorites.size()) {
            cancelFavoriteDrag();
            return;
        }

        FavoriteItem item = favorites.get(dragStartIndex);
        int start = dragStartIndex;
        int target = dragTargetIndex;
        boolean moved = dragMoved && target >= 0 && target != start;
        String key = dragFavoriteKey;

        draggingFavorite = false;
        dragStartIndex = -1;
        dragTargetIndex = -1;
        dragY = -1f;
        dragFavoriteKey = null;
        pressed = -1;
        homePressed = false;
        longPressed = false;
        invalidate();

        if (moved && key != null) {
            callback.reorderFavorite(key, target);
        } else if (item.app != null) {
            callback.editFavorite(item.app);
        } else {
            callback.removeShortcut(item.shortcut);
        }
    }

    private void cancelFavoriteDrag() {
        draggingFavorite = false;
        dragStartIndex = -1;
        dragTargetIndex = -1;
        dragY = -1f;
        dragFavoriteKey = null;
        dragMoved = false;
    }

    private boolean handleBottom(float x, float y) {
        if (mode != HOME || y < getHeight() - dp(110) || callback == null) return false;

        float left = dp(20);
        float right = getWidth() - dp(20);
        float width = right - left;
        float relative = Math.max(0f, Math.min(width, x - left));
        int zone = Math.min(3, (int)(relative / (width / 4f)));

        if (zone == 0) callback.requestPhone();
        else if (zone == 1) callback.requestSearch();
        else if (zone == 2) callback.requestAddShortcut();
        else callback.requestCamera();

        return true;
    }

    private void selectLetter(float y, boolean initial) {
        char old = activeLetter;
        drawerAnchorY = Math.max(dp(135), Math.min(getHeight() - dp(135), y));
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

    private void clearPress() {
        pressed = -1;
        homePressed = false;
        invalidate();
    }

    private boolean inClock(float x, float y) {
        return x >= dp(16) && x <= dp(220) && y >= dp(22) && y <= dp(122);
    }
    private boolean inAlphabet(float x, float y) { return x > getWidth() - dp(52) && y >= alphabetTop() - dp(20) && y <= alphabetBottom() + dp(20); }
    private float alphabetTop() { return dp(124); }
    private float alphabetBottom() { return Math.max(dp(400), getHeight() - dp(104)); }
    private float yForLetter(char c) { return alphabetTop() + (alphabetBottom() - alphabetTop()) * Math.max(0, LETTERS.indexOf(c)) / 25f; }
    private float homeStart() { return Math.max(dp(120), getHeight() * .165f); }

    private float homeRowHeight() {
        float available = Math.max(dp(140), getHeight() - homeStart() - dp(84));
        int desired = Math.max(1, Math.min(favorites.size(), 15));
        float fit = available / desired;
        return Math.max(dp(31), Math.min(dp(47), fit));
    }

    private float overlayRowHeight() { return dp(64); }

    private int maxHomeRows() {
        float available = Math.max(dp(140), getHeight() - homeStart() - dp(84));
        return Math.max(3, (int)(available / homeRowHeight()));
    }
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
        String key(){ return app != null ? app.key() : shortcut.key(); }
    }
}
