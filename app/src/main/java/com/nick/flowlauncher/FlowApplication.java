package com.nick.flowlauncher;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class FlowApplication extends Application {
    private static final String PREFS = "flow_crash";
    private static final String KEY = "last_crash";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY, sw.toString())
                        .commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    public static String getCrash(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
    }

    public static void clearCrash(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY).commit();
    }
}
