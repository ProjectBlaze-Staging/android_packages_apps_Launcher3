/*
 * Copyright (C) 2026 The BlazeAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.RemoteAnimationTarget;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates the "App-to-Dynamic-Island Swallow" flight animation when a closing app is
 * actively playing media/audio/video in the background.
 */
public final class DynamicIslandFlightHelper {

    private static final String KEY_DYNAMIC_ISLAND_SECURE = "ax_dynamic_bar_enabled";
    private static final String KEY_DYNAMIC_ISLAND_LEGACY = "status_bar_show_dynamic_island";
    private static final String KEY_DYNAMIC_ISLAND_MEDIA = "dynamic_island_media_controls";
    private static final String KEY_DYNAMIC_ISLAND_MEDIA_SYSTEM = "status_bar_dynamic_island_media_controls";
    private static final String KEY_PENGUIN_ISLAND_BOUNDS = "penguin_island_bounds";
    private static final String KEY_ACTIVE_MEDIA_PKG = "blaze_island_active_media_pkg";
    private static final String KEY_MEDIA_PLAYING = "blaze_island_media_playing";
    private static final String KEY_SWALLOW_SEQ = "blaze_island_swallow_seq";

    private static final Set<String> KNOWN_MEDIA_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.youtube",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.youtube",
            "app.rvx.android.apps.youtube.music",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.android.chrome",
            "org.schabi.newpipe",
            "com.spotify.music",
            "com.apple.android.music",
            "deezer.android.app",
            "com.aspiro.tidal",
            "com.soundcloud.android",
            "tv.twitch.android.app",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.maxmpz.audioplayer",
            "in.krosbits.musicolet",
            "code.name.monkey.retromusic",
            "com.zionhuang.music",
            "com.fast4x.rimusic",
            "it.fast4x.rimusic",
            "org.lineageos.eleven",
            "org.lineageos.twelve"
    ));

    private static final ExecutorService sBgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DynamicIslandFlightHelper-Bg");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean sInitialized = false;
    private static volatile boolean sIslandMasterEnabled = true;
    private static volatile boolean sIslandMediaEnabled = true;
    private static volatile String sCachedActiveMediaPkg = null;
    private static volatile boolean sCachedIsMediaPlaying = false;
    private static volatile boolean sCachedIsMusicActive = false;
    private static volatile int sCachedAudioMode = AudioManager.MODE_NORMAL;
    private static volatile String sCachedPenguinIslandBounds = null;

    private static final Set<String> sPlayingSessionPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Boolean> sMediaCapableCache =
            new ConcurrentHashMap<>();
    private static final Map<MediaController, MediaController.Callback> sRegisteredControllerCallbacks =
            new ConcurrentHashMap<>();

    private DynamicIslandFlightHelper() {}

    private static void ensureInitialized(Context context) {
        if (sInitialized) return;
        synchronized (DynamicIslandFlightHelper.class) {
            if (sInitialized) return;
            sInitialized = true;
            Context appContext = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            sBgExecutor.execute(() -> initBackground(appContext));
        }
    }

    private static void initBackground(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            updateSettingsCache(cr);

            ContentObserver settingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    sBgExecutor.execute(() -> updateSettingsCache(cr));
                }
            };
            try {
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(KEY_DYNAMIC_ISLAND_SECURE), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(KEY_DYNAMIC_ISLAND_MEDIA), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.System.getUriFor(KEY_DYNAMIC_ISLAND_MEDIA_SYSTEM), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(KEY_PENGUIN_ISLAND_BOUNDS), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.System.getUriFor(KEY_DYNAMIC_ISLAND_LEGACY), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.Global.getUriFor(KEY_ACTIVE_MEDIA_PKG), false, settingsObserver);
                cr.registerContentObserver(
                        Settings.Global.getUriFor(KEY_MEDIA_PLAYING), false, settingsObserver);
            } catch (Throwable ignored) {
            }

            MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
            if (msm != null) {
                HandlerThread ht = new HandlerThread("DynamicIslandMediaSession");
                ht.start();
                Handler bgHandler = new Handler(ht.getLooper());

                MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
                        controllers -> updateMediaSessions(controllers, bgHandler);

                try {
                    msm.addOnActiveSessionsChangedListener(sessionsListener, null, bgHandler);
                    List<MediaController> initialControllers = msm.getActiveSessions(null);
                    updateMediaSessions(initialControllers, bgHandler);
                } catch (Throwable ignored) {
                }
            }

            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) {
                try {
                    sCachedAudioMode = am.getMode();
                    sCachedIsMusicActive = am.isMusicActive();
                    am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                        @Override
                        public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> configs) {
                            sBgExecutor.execute(() -> {
                                try {
                                    sCachedIsMusicActive = am.isMusicActive();
                                    sCachedAudioMode = am.getMode();
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    }, new Handler(Looper.getMainLooper()));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateSettingsCache(ContentResolver cr) {
        try {
            int secureVal = Settings.Secure.getInt(cr, KEY_DYNAMIC_ISLAND_SECURE, -1);
            if (secureVal != -1) {
                sIslandMasterEnabled = secureVal == 1;
            } else {
                sIslandMasterEnabled = Settings.System.getInt(cr, KEY_DYNAMIC_ISLAND_LEGACY, 1) == 1;
            }
            boolean secureMedia = Settings.Secure.getInt(cr, KEY_DYNAMIC_ISLAND_MEDIA, 1) == 1;
            boolean systemMedia = Settings.System.getInt(cr, KEY_DYNAMIC_ISLAND_MEDIA_SYSTEM, 1) == 1;
            sIslandMediaEnabled = secureMedia && systemMedia;
            sCachedPenguinIslandBounds = Settings.Secure.getString(cr, KEY_PENGUIN_ISLAND_BOUNDS);
        } catch (Throwable ignored) {
        }
        try {
            sCachedActiveMediaPkg = Settings.Global.getString(cr, KEY_ACTIVE_MEDIA_PKG);
            sCachedIsMediaPlaying = Settings.Global.getInt(cr, KEY_MEDIA_PLAYING, 0) == 1;
        } catch (Throwable ignored) {
        }
    }

    private static void updateMediaSessions(List<MediaController> controllers, Handler bgHandler) {
        for (Map.Entry<MediaController, MediaController.Callback> entry : sRegisteredControllerCallbacks.entrySet()) {
            try {
                entry.getKey().unregisterCallback(entry.getValue());
            } catch (Throwable ignored) {
            }
        }
        sRegisteredControllerCallbacks.clear();
        Set<String> playing = new HashSet<>();
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (controller == null) continue;
                String pkg = controller.getPackageName();
                PlaybackState state = controller.getPlaybackState();
                if (state != null && isActivePlaybackState(state.getState())) {
                    if (pkg != null) {
                        playing.add(pkg);
                    }
                }
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        if (pkg != null) {
                            if (state != null && isActivePlaybackState(state.getState())) {
                                sPlayingSessionPackages.add(pkg);
                            } else {
                                sPlayingSessionPackages.remove(pkg);
                            }
                        }
                    }
                };
                try {
                    controller.registerCallback(cb, bgHandler);
                    sRegisteredControllerCallbacks.put(controller, cb);
                } catch (Throwable ignored) {
                }
            }
        }
        sPlayingSessionPackages.clear();
        sPlayingSessionPackages.addAll(playing);
    }

    /**
     * Returns true when Dynamic Island is enabled and the closing task is actively playing
     * music/audio/video so its closing window should fly into the Dynamic Island pill.
     */
    public static boolean shouldFlyToDynamicIsland(
            Context context,
            @Nullable RemoteAnimationTarget runningTaskTarget,
            @Nullable String fallbackPackageName) {
        if (context == null) {
            return false;
        }
        ensureInitialized(context);
        if (!sIslandMasterEnabled || !sIslandMediaEnabled) {
            return false;
        }
        String pkg = resolvePackageName(runningTaskTarget, fallbackPackageName);
        if (TextUtils.isEmpty(pkg)) {
            return false;
        }

        // 1. Check system setting broadcast from SystemUI Island controller
        if (sCachedIsMediaPlaying && pkg.equals(sCachedActiveMediaPkg)) {
            return true;
        }

        // 2. Check cached active media session packages
        if (sPlayingSessionPackages.contains(pkg)) {
            return true;
        }

        // 3. Check audio manager call mode
        if ((sCachedAudioMode == AudioManager.MODE_IN_CALL
                || sCachedAudioMode == AudioManager.MODE_IN_COMMUNICATION
                || sCachedAudioMode == AudioManager.MODE_RINGTONE)
                && isCallCapablePackage(pkg)) {
            return true;
        }

        // 4. Check cached music active
        if (sCachedIsMusicActive && isMediaCapablePackage(context, pkg)) {
            return true;
        }

        return false;
    }

    /**
     * Returns the target capsule bounds of the Dynamic Island pill in Launcher window coordinates.
     * Parses SystemUI's live "penguin_island_bounds" ("left top right bottom") when available.
     */
    public static RectF getDynamicIslandTargetRect(Context context, DeviceProfile dp) {
        ensureInitialized(context);
        String rawBounds = sCachedPenguinIslandBounds;
        if (TextUtils.isEmpty(rawBounds)) {
            try {
                rawBounds = Settings.Secure.getString(
                        context.getContentResolver(), KEY_PENGUIN_ISLAND_BOUNDS);
                sCachedPenguinIslandBounds = rawBounds;
            } catch (Throwable ignored) {
            }
        }
        if (!TextUtils.isEmpty(rawBounds)) {
            try {
                String[] parts = rawBounds.trim().split("\\s+");
                if (parts.length == 4) {
                    float l = Float.parseFloat(parts[0]);
                    float t = Float.parseFloat(parts[1]);
                    float r = Float.parseFloat(parts[2]);
                    float b = Float.parseFloat(parts[3]);
                    if (r > l + 24f && b > t + 12f) {
                        return new RectF(l, t, r, b);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        float density = context.getResources().getDisplayMetrics().density;
        float screenWidth = dp.getDeviceProperties().getWidthPx();
        float pillWidth = 148f * density;
        float pillHeight = 32f * density;
        float top = 8f * density;
        float left = (screenWidth - pillWidth) / 2f;
        return new RectF(left, top, left + pillWidth, top + pillHeight);
    }

    /**
     * Signals SystemUI's Dynamic Island to switch to the Media chip and play the elastic
     * "Swallow & Swell" bounce animation as the closing app window enters the pill.
     */
    public static void triggerDynamicIslandSwallow(Context context) {
        if (context == null) return;
        sBgExecutor.execute(() -> {
            try {
                ContentResolver cr = context.getContentResolver();
                int nextSeq = Settings.Global.getInt(cr, KEY_SWALLOW_SEQ, 0) + 1;
                Settings.Global.putInt(cr, KEY_SWALLOW_SEQ, nextSeq);
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean isDynamicIslandMediaEnabled(Context context) {
        if (context == null) return false;
        ensureInitialized(context);
        return sIslandMasterEnabled && sIslandMediaEnabled;
    }

    @Nullable
    private static String resolvePackageName(
            @Nullable RemoteAnimationTarget target, @Nullable String fallback) {
        if (target != null && target.taskInfo != null) {
            ActivityManager.RunningTaskInfo ti = target.taskInfo;
            ComponentName top = ti.topActivity;
            if (top != null && !TextUtils.isEmpty(top.getPackageName())) {
                return top.getPackageName();
            }
            ComponentName base = ti.baseActivity;
            if (base != null && !TextUtils.isEmpty(base.getPackageName())) {
                return base.getPackageName();
            }
            if (ti.baseIntent != null && ti.baseIntent.getComponent() != null) {
                return ti.baseIntent.getComponent().getPackageName();
            }
        }
        return fallback;
    }

    private static boolean isActivePlaybackState(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
    }

    private static boolean isCallCapablePackage(String pkg) {
        String lower = pkg.toLowerCase();
        return lower.contains("dialer")
                || lower.contains("incallui")
                || lower.contains("telecom")
                || lower.contains("phone")
                || lower.contains("whatsapp")
                || lower.contains("telegram")
                || lower.contains("discord")
                || lower.contains("skype")
                || lower.contains("viber")
                || lower.contains("signal");
    }

    private static boolean isMediaCapablePackage(Context context, String pkg) {
        if (KNOWN_MEDIA_PACKAGES.contains(pkg)) {
            return true;
        }
        String lower = pkg.toLowerCase();
        if (lower.contains("youtube") || lower.contains("music") || lower.contains("spotify")
                || lower.contains("video") || lower.contains("player") || lower.contains("media")
                || lower.contains("podcast") || lower.contains("radio")) {
            return true;
        }
        Boolean cached = sMediaCapableCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        sBgExecutor.execute(() -> {
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                boolean capable = ai != null && (ai.category == ApplicationInfo.CATEGORY_AUDIO
                        || ai.category == ApplicationInfo.CATEGORY_VIDEO);
                sMediaCapableCache.put(pkg, capable);
            } catch (Throwable ignored) {
                sMediaCapableCache.put(pkg, false);
            }
        });
        return false;
    }
}
