package com.nick.flowlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity implements LauncherView.Callback {
    private static final int PICK_SHORTCUT_ICON = 5021;
    private static final int REQUEST_HOME_ROLE = 5022;

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
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private volatile boolean loading = false;
    private boolean firstResume = true;
    private TextView loadingView;

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
        launcherView.postDelayed(this::reloadDataAsync, 250);
        launcherView.postDelayed(this::requestHomeRoleIfNeeded, 800);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onCreate already starts the initial load. Do not immediately scan every app twice.
        if (firstResume) {
            firstResume = false;
        }
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

    private void requestHomeRoleIfNeeded() {
        if (isFinishing() || isDestroyed()) return;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roleManager = getSystemService(RoleManager.class);
                if (roleManager != null
                        && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                            REQUEST_HOME_ROLE);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // Fallback for devices/ROMs that do not expose the Home role request dialog.
        try {
            Intent homeSettings = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(homeSettings);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
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
        searchField.setTextColor(Color.WHITE);
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextSize(18f);
        searchField.setBackground(new ColorDrawable(0x33000000));
        searchField.setPadding(dp(18), 0, dp(18), 0);
        searchField.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                launcherView.setSearchQuery(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        FrameLayout.LayoutParams searchParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(58));
        searchParams.gravity = Gravity.BOTTOM;
        searchParams.setMargins(dp(22), 0, dp(22), dp(84));
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

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    apps = loadedApps;
                    shortcuts = loadedShortcuts;
                    favoriteKeys = loadedFavorites;
                    launcherView.setData(apps, shortcuts, favoriteKeys);
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
        launcherView.setData(apps, shortcuts, favoriteKeys);
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
        searchField.setAlpha(0f);
        searchField.setTranslationY(dp(18));
        searchField.animate().alpha(1f).translationY(0f).setDuration(180).start();
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        launcherView.setSearchMode(true);
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

    @Override
    public void onBackPressed() {
        if (searchField != null && searchField.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (launcherView != null && launcherView.closeOverlay()) return;
        // A launcher stays on Home; Back from the clean home screen should not exit it.
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
