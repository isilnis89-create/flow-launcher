package com.nick.flowlauncher;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.WindowInsets;
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

public final class LauncherActivity extends Activity implements LauncherView.Callback {
    private static final int PICK_SHORTCUT_ICON = 5021;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        repository = new LauncherRepository(this);
        buildUi();
        reloadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null && launcherView != null) reloadData();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            if (window.getInsetsController() != null) {
                window.getInsetsController().hide(WindowInsets.Type.statusBars());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
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
        searchParams.setMargins(dp(22), 0, dp(22), dp(28));
        root.addView(searchField, searchParams);
        setContentView(root);
    }

    private void reloadData() {
        apps = repository.loadApps();
        shortcuts = repository.loadShortcuts();
        favoriteKeys = repository.getFavoriteKeys(apps);
        launcherView.setData(apps, shortcuts, favoriteKeys);
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
        super.onBackPressed();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
