package com.nick.flowlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LauncherRepository {
    private static final String PREFS = "flow_launcher";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_SHORTCUTS = "shortcuts";

    private final Context context;
    private final SharedPreferences prefs;
    private final PackageManager pm;

    public LauncherRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.pm = this.context.getPackageManager();
    }

    public List<AppEntry> loadApps() {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(query, PackageManager.MATCH_ALL);
        } catch (Throwable t) {
            infos = new ArrayList<>();
        }
        List<AppEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int size = dp(52);

        for (ResolveInfo info : infos) {
            try {
                if (info == null || info.activityInfo == null) continue;
                if (context.getPackageName().equals(info.activityInfo.packageName)) continue;
                ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                String key = component.flattenToShortString();
                if (!seen.add(key)) continue;

                String label;
                try {
                    CharSequence cs = info.loadLabel(pm);
                    label = cs == null ? info.activityInfo.packageName : cs.toString();
                } catch (Throwable ignored) {
                    label = info.activityInfo.packageName;
                }

                Bitmap icon = null;
                try {
                    icon = minimalIcon(info.loadIcon(pm), size);
                } catch (Throwable ignored) {
                    // A broken or unusual app icon must never stop the launcher from starting.
                }
                out.add(new AppEntry(label, component, icon));
            } catch (Throwable ignored) {
                // Skip malformed package entries instead of crashing the whole launcher.
            }
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    public List<String> getFavoriteKeys(List<AppEntry> apps) {
        List<String> keys = readStringArray(prefs.getString(KEY_FAVORITES, null));
        if (keys.isEmpty()) {
            String[] preferredPackages = {
                    "com.whatsapp", "com.google.android.apps.messaging", "com.android.chrome",
                    "com.sec.android.app.camera", "com.spotify.music", "com.openai.chatgpt"
            };
            for (String pkg : preferredPackages) {
                for (AppEntry app : apps) {
                    if (app.component.getPackageName().equals(pkg)) {
                        keys.add(app.key());
                        break;
                    }
                }
            }
            for (AppEntry app : apps) {
                if (keys.size() >= 7) break;
                if (!keys.contains(app.key())) keys.add(app.key());
            }
            saveFavoriteKeys(keys);
        }
        return keys;
    }

    public void saveFavoriteKeys(List<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) array.put(key);
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply();
    }

    public List<WebShortcut> loadShortcuts() {
        List<WebShortcut> out = new ArrayList<>();
        String json = prefs.getString(KEY_SHORTCUTS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String name = obj.optString("name", "Website");
                String url = obj.optString("url", "");
                Bitmap icon = loadShortcutIcon(id);
                if (!id.isEmpty() && !url.isEmpty()) out.add(new WebShortcut(id, name, url, icon));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public WebShortcut addShortcut(String name, String url, Bitmap icon) {
        String clean = url == null ? "" : url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) clean = "https://" + clean;
        String id = UUID.randomUUID().toString();
        if (icon != null) saveShortcutIcon(id, icon);

        JSONArray arr;
        try {
            arr = new JSONArray(prefs.getString(KEY_SHORTCUTS, "[]"));
        } catch (JSONException e) {
            arr = new JSONArray();
        }
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name == null || name.trim().isEmpty() ? hostName(clean) : name.trim());
            obj.put("url", clean);
            arr.put(obj);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_SHORTCUTS, arr.toString()).apply();
        return new WebShortcut(id, obj.optString("name", "Website"), clean, icon);
    }

    public void removeShortcut(String id) {
        try {
            JSONArray old = new JSONArray(prefs.getString(KEY_SHORTCUTS, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject obj = old.optJSONObject(i);
                if (obj != null && !id.equals(obj.optString("id"))) next.put(obj);
            }
            prefs.edit().putString(KEY_SHORTCUTS, next.toString()).apply();
        } catch (JSONException ignored) {
        }
        File file = shortcutIconFile(id);
        if (file.exists()) file.delete();
    }

    public Bitmap importIcon(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open image");
            Drawable drawable = Drawable.createFromStream(input, uri.toString());
            if (drawable == null) throw new IOException("Unsupported image");
            return drawableToBitmap(drawable, dp(128));
        }
    }

    public Map<String, AppEntry> indexApps(List<AppEntry> apps) {
        Map<String, AppEntry> map = new HashMap<>();
        for (AppEntry app : apps) map.put(app.key(), app);
        return map;
    }

    private Bitmap loadShortcutIcon(String id) {
        File file = shortcutIconFile(id);
        if (!file.exists()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            return android.graphics.BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            return null;
        }
    }

    private void saveShortcutIcon(String id, Bitmap bitmap) {
        File file = shortcutIconFile(id);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out);
        } catch (IOException ignored) {
        }
    }

    private File shortcutIconFile(String id) {
        return new File(context.getFilesDir(), "shortcut_" + id + ".png");
    }

    private List<String> readStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private Bitmap minimalIcon(Drawable drawable, int size) {
        if (drawable == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // Frosted "glass" disc: consistent launcher styling without destroying app identity.
            Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
            plate.setColor(0x9A20242B);
            canvas.drawCircle(size / 2f, size / 2f, size * 0.44f, plate);

            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, size * 0.025f));
            rim.setColor(0x55FFFFFF);
            canvas.drawCircle(size / 2f, size / 2f, size * 0.43f, rim);

            int innerSize = Math.max(1, Math.round(size * 0.62f));
            Bitmap artwork = drawableToBitmap(drawable, innerSize);
            if (artwork != null) {
                Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0.58f);
                iconPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
                iconPaint.setAlpha(245);

                float left = (size - innerSize) / 2f;
                float top = (size - innerSize) / 2f;
                canvas.drawBitmap(artwork, left, top, iconPaint);
            }
            return bitmap;
        } catch (Throwable t) {
            return drawableToBitmap(drawable, size);
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable, int size) {
        if (drawable == null) return null;
        try {
            if (drawable instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) drawable).getBitmap();
                if (b != null && b.getWidth() == size && b.getHeight() == size) return b;
            }
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private String hostName(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null || host.isEmpty()) return "Website";
            if (host.startsWith("www.")) host = host.substring(4);
            int dot = host.indexOf('.');
            String base = dot > 0 ? host.substring(0, dot) : host;
            if (base.isEmpty()) return "Website";
            return Character.toUpperCase(base.charAt(0)) + base.substring(1);
        } catch (Exception e) {
            return "Website";
        }
    }
}
