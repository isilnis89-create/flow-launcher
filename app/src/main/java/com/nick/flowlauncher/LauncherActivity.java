package com.nick.flowlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity implements LauncherView.Callback {
    private static final int PICK_SHORTCUT_ICON = 5021;
    private static final int REQUEST_HOME_ROLE = 5022;
    private static final int PICK_FAVORITE_ICON = 5023;

    private LauncherRepository repository;
    private LauncherView launcherView;
    private EditText searchField;
    private Bitmap pendingShortcutIcon;
    private TextView pendingIconLabel;
    private EditText pendingName;
    private EditText pendingUrl;
    private AlertDialog pendingShortcutDialog;
    private List<AppEntry> apps = new ArrayList<>();
    private List<WebShortcut> shortcuts = new ArrayList<>();
    private List<String> favoriteKeys = new ArrayList<>();
    private Map<String, FavoriteOverride> favoriteOverrides = new HashMap<>();
    private String pendingFavoriteKey;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private volatile boolean loading = false;
    private boolean firstResume = true;
    private TextView loadingView;
    private boolean packageReceiverRegistered;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!loading) reloadDataAsync();
        }
    };
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable statePoller = new Runnable() {
        @Override public void run() {
            if (launcherView != null) {
                launcherView.setNotificationState(
                        FlowNotificationService.notificationSnapshot(),
                        FlowNotificationService.tidalSnapshot());
            }
            stateHandler.postDelayed(this, 850);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String previousCrash = FlowApplication.getCrash(this);
        if (previousCrash != null && !previousCrash.isEmpty()) {
            startActivity(new Intent(this, CrashReportActivity.class));
            finish();
            return;
        }

        configureWindow();
        repository = new LauncherRepository(this);
        buildUi();
        launcherView.setAccentColor(repository.getAccentColor());
        applyViewPreferences();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack);
        }
        registerPackageReceiver();
        launcherView.postDelayed(this::reloadDataAsync, 250);
        launcherView.postDelayed(this::requestHomeRoleIfNeeded, 800);
        launcherView.postDelayed(this::maybePromptNotificationAccess, 2600);
    }

    @Override
    protected void onResume() {
        super.onResume();
        stateHandler.removeCallbacks(statePoller);
        stateHandler.post(statePoller);
        if (firstResume) {
            firstResume = false;
        }
    }

    @Override
    protected void onPause() {
        stateHandler.removeCallbacks(statePoller);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Pressing Home while Flow is already visible should always return to the clean home screen.
        if (searchField != null && searchField.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else if (launcherView != null) {
            launcherView.closeOverlay();
        }
    }

    private SharedPreferences uiPrefs() {
        return getSharedPreferences("flow_launcher_ui", MODE_PRIVATE);
    }

    private void applyViewPreferences() {
        if (launcherView == null) return;
        launcherView.setAmbientEnabled(uiPrefs().getBoolean("ambient_enabled", true));
        launcherView.setFavoriteDensity(uiPrefs().getInt("favorite_density", 16));
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void maybePromptNotificationAccess() {
        if (isFinishing() || isDestroyed() || !isDefaultHome()) return;
        if (hasNotificationAccess()) return;
        if (uiPrefs().getBoolean("notification_prompted", false)) return;

        uiPrefs().edit().putBoolean("notification_prompted", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Enable Flow peeks & Tidal")
                .setMessage("Notification access lets Flow show unread indicators, swipe-to-peek notifications, and the Tidal now-playing controls. Flow does not send this data anywhere.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Enable", (dialog, which) -> openNotificationAccessSettings())
                .show();
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "Open Settings > Notifications > Notification access", Toast.LENGTH_LONG).show();
        }
    }

    private void registerPackageReceiver() {
        if (packageReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(packageReceiver, filter);
            }
            packageReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void requestHomeRoleIfNeeded() {
        if (isFinishing() || isDestroyed()) return;

        // If Android already considers Flow the Home app, do absolutely nothing.
        if (isDefaultHome()) return;

        boolean alreadyPrompted = getSharedPreferences("flow_launcher_ui", MODE_PRIVATE)
                .getBoolean("home_role_prompted", false);
        if (alreadyPrompted) return;

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roleManager = getSystemService(RoleManager.class);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return;

                    getSharedPreferences("flow_launcher_ui", MODE_PRIVATE)
                            .edit().putBoolean("home_role_prompted", true).apply();
                    startActivityForResult(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                            REQUEST_HOME_ROLE);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            getSharedPreferences("flow_launcher_ui", MODE_PRIVATE)
                    .edit().putBoolean("home_role_prompted", true).apply();
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo info =
                    getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return info != null
                    && info.activityInfo != null
                    && getPackageName().equals(info.activityInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        stateHandler.removeCallbacksAndMessages(null);
        if (packageReceiverRegistered) {
            try { unregisterReceiver(packageReceiver); } catch (Throwable ignored) { }
            packageReceiverRegistered = false;
        }
        loader.shutdownNow();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        // Keep window configuration conservative for broad One UI compatibility.
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        launcherView = new LauncherView(this);
        launcherView.setCallback(this);
        root.addView(launcherView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setHint("Search apps");
        // Hidden input: swipe-up search uses the keyboard, while the typed query is drawn by LauncherView.
        searchField.setTextColor(Color.TRANSPARENT);
        searchField.setHintTextColor(Color.TRANSPARENT);
        searchField.setTextSize(1f);
        searchField.setBackgroundColor(Color.TRANSPARENT);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setAlpha(0.01f);
        searchField.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                launcherView.setSearchQuery(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        FrameLayout.LayoutParams searchParams = new FrameLayout.LayoutParams(dp(2), dp(2));
        searchParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        root.addView(searchField, searchParams);

        loadingView = new TextView(this);
        loadingView.setText("Loading apps…");
        loadingView.setTextColor(0xDDFFFFFF);
        loadingView.setTextSize(16f);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setBackgroundColor(0x22000000);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(42));
        loadingParams.gravity = Gravity.CENTER;
        loadingParams.leftMargin = dp(24);
        loadingParams.rightMargin = dp(24);
        root.addView(loadingView, loadingParams);

        setContentView(root);
    }

    private void reloadDataAsync() {
        if (loading || repository == null || launcherView == null) return;
        loading = true;
        if (loadingView != null) loadingView.setVisibility(View.VISIBLE);

        loader.execute(() -> {
            try {
                List<AppEntry> loadedApps = repository.loadApps();
                List<WebShortcut> loadedShortcuts = repository.loadShortcuts();
                List<String> loadedFavorites = repository.getFavoriteKeys(loadedApps);
                Map<String, FavoriteOverride> loadedOverrides = repository.loadFavoriteOverrides();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    apps = loadedApps;
                    shortcuts = loadedShortcuts;
                    favoriteKeys = loadedFavorites;
                    favoriteOverrides = loadedOverrides;
                    launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
                    if (loadingView != null) loadingView.setVisibility(View.GONE);
                    loading = false;
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (loadingView != null) {
                        loadingView.setText("Couldn't load apps — tap to retry");
                        loadingView.setOnClickListener(v -> {
                            loadingView.setText("Loading apps…");
                            loading = false;
                            reloadDataAsync();
                        });
                    }
                    loading = false;
                    Toast.makeText(this, "Flow Launcher hit a startup error", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void reloadData() {
        reloadDataAsync();
    }

    @Override
    public void launchApp(AppEntry app) {
        if (app == null) return;
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(app.component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "App is no longer available", Toast.LENGTH_SHORT).show();
            reloadData();
        }
    }

    @Override
    public void openWebShortcut(WebShortcut shortcut) {
        if (shortcut == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open this shortcut", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void requestAddShortcut() {
        showShortcutDialog();
    }

    @Override
    public void toggleFavorite(AppEntry app) {
        if (app == null) return;
        String key = app.key();
        if (favoriteKeys.contains(key)) {
            favoriteKeys.remove(key);
            Toast.makeText(this, "Removed from favourites", Toast.LENGTH_SHORT).show();
        } else {
            favoriteKeys.add(key);
            Toast.makeText(this, "Added to favourites", Toast.LENGTH_SHORT).show();
        }
        repository.saveFavoriteKeys(favoriteKeys);
        launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
    }

    @Override
    public void editFavorite(AppEntry app) {
        if (app == null) return;
        String key = app.key();
        FavoriteOverride current = favoriteOverrides.get(key);
        String currentName = current != null && current.name != null && !current.name.isEmpty()
                ? current.name : app.label;

        String[] actions = {
                "Rename",
                "Change icon",
                "App shortcuts",
                "Move up",
                "Move down",
                "Reset name & icon",
                "Remove from favourites"
        };

        new AlertDialog.Builder(this)
                .setTitle(currentName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showRenameFavoriteDialog(app);
                    else if (which == 1) pickFavoriteIcon(app);
                    else if (which == 2) showAppShortcuts(app);
                    else if (which == 3) moveFavorite(key, -1);
                    else if (which == 4) moveFavorite(key, 1);
                    else if (which == 5) {
                        repository.resetFavoriteOverride(key);
                        refreshFavoriteOverrides();
                    } else if (which == 6) {
                        favoriteKeys.remove(key);
                        repository.saveFavoriteKeys(favoriteKeys);
                        launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
                    }
                })
                .show();
    }

    private void showAppShortcuts(AppEntry app) {
        if (app == null || Build.VERSION.SDK_INT < 25) return;
        try {
            LauncherApps launcherApps = getSystemService(LauncherApps.class);
            if (launcherApps == null || !launcherApps.hasShortcutHostPermission()) {
                Toast.makeText(this, "App shortcuts require Flow to be the default launcher", Toast.LENGTH_LONG).show();
                return;
            }

            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                    .setPackage(app.component.getPackageName())
                    .setQueryFlags(
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);

            List<ShortcutInfo> found = launcherApps.getShortcuts(query, android.os.Process.myUserHandle());
            if (found == null) found = new ArrayList<>();

            List<ShortcutInfo> usable = new ArrayList<>();
            for (ShortcutInfo info : found) {
                if (info != null && info.isEnabled()) usable.add(info);
            }

            if (usable.isEmpty()) {
                Toast.makeText(this, "This app doesn't publish any launcher shortcuts", Toast.LENGTH_SHORT).show();
                return;
            }

            CharSequence[] labels = new CharSequence[usable.size()];
            for (int i = 0; i < usable.size(); i++) {
                ShortcutInfo info = usable.get(i);
                CharSequence label = info.getShortLabel();
                if (label == null || label.length() == 0) label = info.getLongLabel();
                labels[i] = label == null ? "Shortcut" : label;
            }

            List<ShortcutInfo> finalUsable = usable;
            new AlertDialog.Builder(this)
                    .setTitle(app.label)
                    .setItems(labels, (dialog, which) -> {
                        try {
                            launcherApps.startShortcut(finalUsable.get(which), null, null);
                        } catch (Throwable t) {
                            Toast.makeText(this, "Couldn't open that shortcut", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Toast.makeText(this, "App shortcuts aren't available for this app", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameFavoriteDialog(AppEntry app) {
        String key = app.key();
        FavoriteOverride current = favoriteOverrides.get(key);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(current != null && current.name != null && !current.name.isEmpty()
                ? current.name : app.label);
        input.setPadding(dp(20), dp(8), dp(20), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("Rename favourite")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    repository.saveFavoriteName(key, input.getText().toString());
                    refreshFavoriteOverrides();
                })
                .show();
    }

    private void pickFavoriteIcon(AppEntry app) {
        pendingFavoriteKey = app.key();
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        startActivityForResult(pick, PICK_FAVORITE_ICON);
    }

    private void moveFavorite(String key, int direction) {
        int index = favoriteKeys.indexOf(key);
        if (index < 0) return;
        int target = index + direction;
        if (target < 0 || target >= favoriteKeys.size()) return;

        Collections.swap(favoriteKeys, index, target);
        repository.saveFavoriteKeys(favoriteKeys);
        launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
    }

    private void refreshFavoriteOverrides() {
        favoriteOverrides = repository.loadFavoriteOverrides();
        launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
    }

    @Override
    public void removeShortcut(WebShortcut shortcut) {
        if (shortcut == null) return;
        favoriteKeys.remove(shortcut.key());
        repository.saveFavoriteKeys(favoriteKeys);
        repository.removeShortcut(shortcut.id);
        reloadData();
        Toast.makeText(this, "Shortcut removed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void requestSearch() {
        if (searchField.getVisibility() == View.VISIBLE) return;
        searchField.setVisibility(View.VISIBLE);
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        launcherView.setSearchMode(true);
    }

    @Override
    public void requestPhone() {
        try {
            Intent dial = new Intent(Intent.ACTION_DIAL);
            startActivity(dial);
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't open the phone app", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void requestCamera() {
        try {
            Intent camera = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            startActivity(camera);
            return;
        } catch (Throwable ignored) {
        }

        try {
            Intent samsungCamera = getPackageManager().getLaunchIntentForPackage("com.sec.android.app.camera");
            if (samsungCamera != null) {
                startActivity(samsungCamera);
                return;
            }
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void requestClock() {
        try {
            Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            startActivity(alarms);
            return;
        } catch (Throwable ignored) {
        }

        try {
            Intent samsungClock = getPackageManager().getLaunchIntentForPackage("com.sec.android.app.clockpackage");
            if (samsungClock != null) {
                startActivity(samsungClock);
                return;
            }
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "Couldn't open the clock app", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void reorderFavorite(String key, int targetIndex) {
        if (key == null) return;
        int current = favoriteKeys.indexOf(key);
        if (current < 0) return;

        String item = favoriteKeys.remove(current);
        int target = Math.max(0, Math.min(targetIndex, favoriteKeys.size()));
        favoriteKeys.add(target, item);
        repository.saveFavoriteKeys(favoriteKeys);
        launcherView.setData(apps, shortcuts, favoriteKeys, favoriteOverrides);
    }

    @Override
    public void requestNotificationPeek(String packageName) {
        if (packageName == null) return;
        FlowNotificationInfo info = FlowNotificationService.notificationSnapshot().get(packageName);
        if (info == null || info.count <= 0) {
            Toast.makeText(this, "No notification to show", Toast.LENGTH_SHORT).show();
            return;
        }

        String title = info.title == null || info.title.isEmpty() ? "Notification" : info.title;
        String message = info.text == null ? "" : info.text;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setPositiveButton("Open app", (dialog, which) -> {
                    try {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                        if (launch != null) startActivity(launch);
                    } catch (Throwable ignored) {
                    }
                })
                .show();
    }

    @Override
    public void requestFlowSettings() {
        showFlowSettings();
    }

    private void showFlowSettings() {
        boolean ambient = uiPrefs().getBoolean("ambient_enabled", true);
        int density = uiPrefs().getInt("favorite_density", 16);
        int iconStyle = repository.getIconStyle();

        String[] rows = {
                "Icon style  ·  " + iconStyleName(iconStyle),
                "Favourite density  ·  " + density,
                "Ambient mode  ·  " + (ambient ? "On" : "Off"),
                "Notification / Tidal access  ·  " + (hasNotificationAccess() ? "Enabled" : "Disabled"),
                "Default Home app settings"
        };

        new AlertDialog.Builder(this)
                .setTitle("Flow settings")
                .setItems(rows, (dialog, which) -> {
                    if (which == 0) showIconStylePicker();
                    else if (which == 1) showDensityPicker();
                    else if (which == 2) {
                        boolean next = !uiPrefs().getBoolean("ambient_enabled", true);
                        uiPrefs().edit().putBoolean("ambient_enabled", next).apply();
                        applyViewPreferences();
                    } else if (which == 3) {
                        openNotificationAccessSettings();
                    } else {
                        requestLauncherSettings();
                    }
                })
                .show();
    }

    private String iconStyleName(int style) {
        if (style == LauncherRepository.ICON_MONO) return "Monochrome";
        if (style == LauncherRepository.ICON_ORIGINAL) return "Original";
        return "Flow duotone";
    }

    private void showIconStylePicker() {
        String[] styles = {"Flow duotone", "Monochrome", "Original"};
        int current = repository.getIconStyle();
        new AlertDialog.Builder(this)
                .setTitle("Icon style")
                .setSingleChoiceItems(styles, current, (dialog, which) -> {
                    repository.setIconStyle(which);
                    dialog.dismiss();
                    reloadDataAsync();
                })
                .show();
    }

    private void showDensityPicker() {
        String[] labels = {"Comfortable · 12", "Balanced · 16", "Maximum · 18"};
        int[] values = {12, 16, 18};
        int currentValue = uiPrefs().getInt("favorite_density", 16);
        int checked = currentValue <= 13 ? 0 : (currentValue >= 18 ? 2 : 1);

        new AlertDialog.Builder(this)
                .setTitle("Favourite density")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    uiPrefs().edit().putInt("favorite_density", values[which]).apply();
                    applyViewPreferences();
                    dialog.dismiss();
                })
                .show();
    }

    @Override
    public void requestTidalAction(int action) {
        if (!FlowNotificationService.sendTidalAction(action)) {
            Toast.makeText(this, "Tidal control isn't available yet", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void openTidal() {
        if (FlowNotificationService.openTidal()) return;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.aspiro.tidal");
            if (launch != null) {
                startActivity(launch);
                return;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "Couldn't open Tidal", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void requestLauncherSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Long-press apps to add or remove favourites", Toast.LENGTH_LONG).show();
        }
    }

    public void closeSearch() {
        if (searchField.getVisibility() != View.VISIBLE) return;
        searchField.setText("");
        searchField.clearFocus();
        searchField.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        launcherView.setSearchMode(false);
    }

    private void showShortcutDialog() {
        pendingShortcutIcon = null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(8), dp(22), 0);

        pendingName = new EditText(this);
        pendingName.setHint("Name, e.g. Raider.IO");
        pendingName.setSingleLine(true);
        layout.addView(pendingName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pendingUrl = new EditText(this);
        pendingUrl.setHint("Website URL");
        pendingUrl.setSingleLine(true);
        pendingUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(pendingUrl, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button chooseIcon = new Button(this);
        chooseIcon.setText("Choose custom icon");
        chooseIcon.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            startActivityForResult(pick, PICK_SHORTCUT_ICON);
        });
        layout.addView(chooseIcon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pendingIconLabel = new TextView(this);
        pendingIconLabel.setText("No icon selected — a globe icon will be used");
        pendingIconLabel.setTextSize(13f);
        pendingIconLabel.setPadding(dp(4), dp(6), 0, 0);
        layout.addView(pendingIconLabel);

        pendingShortcutDialog = new AlertDialog.Builder(this)
                .setTitle("Add website shortcut")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null)
                .create();
        pendingShortcutDialog.setOnShowListener(dialog -> pendingShortcutDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> savePendingShortcut()));
        pendingShortcutDialog.show();
    }

    private void savePendingShortcut() {
        String url = pendingUrl == null ? "" : pendingUrl.getText().toString().trim();
        String name = pendingName == null ? "" : pendingName.getText().toString().trim();
        if (url.isEmpty()) {
            pendingUrl.setError("Enter a website URL");
            return;
        }
        WebShortcut shortcut = repository.addShortcut(name, url, pendingShortcutIcon);
        if (!favoriteKeys.contains(shortcut.key())) favoriteKeys.add(shortcut.key());
        repository.saveFavoriteKeys(favoriteKeys);
        if (pendingShortcutDialog != null) pendingShortcutDialog.dismiss();
        reloadData();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FAVORITE_ICON && resultCode == RESULT_OK
                && data != null && data.getData() != null && pendingFavoriteKey != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            try {
                Bitmap icon = repository.importIcon(uri);
                repository.saveFavoriteIcon(pendingFavoriteKey, icon);
                refreshFavoriteOverrides();
            } catch (IOException e) {
                Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show();
            }
            pendingFavoriteKey = null;
            return;
        }

        if (requestCode == PICK_SHORTCUT_ICON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            try {
                pendingShortcutIcon = repository.importIcon(uri);
                if (pendingIconLabel != null) pendingIconLabel.setText("Custom icon selected ✓");
            } catch (IOException e) {
                Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleBack() {
        if (searchField != null && searchField.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (launcherView != null && launcherView.closeOverlay()) return;
        // On the clean home screen Back intentionally does nothing.
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
