/*
 * Copyright (C) 2026 The BlazeAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;

/**
 * Loads icons from an ADW / Nova / Theme Park compatible icon pack.
 *
 * The active pack is stored by BlazeHouse in {@link Settings.Secure} under {@link #SETTING_KEY}:
 * <ul>
 *   <li>unset / empty: the stock BlazeIcons pack (if installed)</li>
 *   <li>{@link #NONE}: stock (LineageOS) icons</li>
 *   <li>any package name: that icon pack</li>
 * </ul>
 */
public class BlazeIconPackManager {

    private static final String TAG = "BlazeIconPack";

    public static final String SETTING_KEY = "blaze_icon_pack";
    public static final String DEFAULT_PACK = "org.blazeos.blazeicons";
    public static final String STYLE_DIMENSIONAL = "dimensional";
    public static final String STYLE_CLASSIC = "classic";
    public static final String STYLE_ADAPTIVE = "adaptive";
    public static final String STYLE_LIQUID_GLASS = "liquid_glass";
    public static final String NONE = "none";

    private static final String COMPONENT_PREFIX = "ComponentInfo{";
    private static final String AMBIGUOUS = "";

    private final Context mContext;

    private String mLoadedPack = "";
    private Resources mPackResources;
    /** flattened component ("pkg/cls") -> drawable name */
    private final ArrayMap<String, String> mComponentMap = new ArrayMap<>();
    /** package -> drawable name when the package has exactly one mapping, else AMBIGUOUS */
    private final ArrayMap<String, String> mPackageMap = new ArrayMap<>();

    public BlazeIconPackManager(Context context) {
        mContext = context;
    }

    /**
     * Returns the package/style code of the icon pack that should currently be applied, or an empty
     * string when stock icons should be used.
     */
    public static String resolveSelectedPack(Context context) {
        String value = null;
        try {
            value = Settings.Secure.getString(context.getContentResolver(), SETTING_KEY);
        } catch (Exception e) {
            Log.w(TAG, "Unable to read " + SETTING_KEY, e);
        }
        if (TextUtils.isEmpty(value)) {
            value = DEFAULT_PACK;
        }
        if (NONE.equals(value) || STYLE_CLASSIC.equals(value) || STYLE_ADAPTIVE.equals(value)) {
            return "";
        }
        if (STYLE_DIMENSIONAL.equals(value) || STYLE_LIQUID_GLASS.equals(value)) {
            return isInstalled(context, DEFAULT_PACK) ? DEFAULT_PACK : "";
        }
        return isInstalled(context, value) ? value : "";
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Loads the icon for the given component from {@code pack}. Returns null when the pack does
     * not theme the component, so the caller can fall back to the application icon.
     */
    @Nullable
    public synchronized Drawable loadIcon(
            String pack, String packageName, @Nullable String className, int iconDpi) {
        if (TextUtils.isEmpty(pack) || TextUtils.isEmpty(packageName)) {
            return null;
        }
        String actualPack = (STYLE_DIMENSIONAL.equals(pack) || STYLE_LIQUID_GLASS.equals(pack))
                ? DEFAULT_PACK : pack;
        if (!ensureLoaded(actualPack)) {
            return null;
        }

        String name = null;
        if (!TextUtils.isEmpty(className)) {
            name = mComponentMap.get(new ComponentName(packageName, className).flattenToString());
        }
        if (name == null) {
            String byPackage = mPackageMap.get(packageName);
            if (!TextUtils.isEmpty(byPackage)) {
                name = byPackage;
            }
        }
        if (name == null) {
            return null;
        }
        return loadDrawable(pack, actualPack, name, iconDpi);
    }

    @Nullable
    private Drawable loadDrawable(String requestedStyle, String actualPack, String name, int iconDpi) {
        Resources res = mPackResources;
        int id = 0;

        // BlazeIcons ships static full-bleed "pack_*" plates (Dimensional) plus matching adaptive "ic_*"
        // icons. Prefer full-bleed "pack_*" when Dimensional is selected, and "ic_*" otherwise.
        if (STYLE_DIMENSIONAL.equals(requestedStyle)) {
            if (name.startsWith("ic_")) {
                id = res.getIdentifier("pack_" + name.substring("ic_".length()), "drawable", actualPack);
            }
            if (id == 0) {
                id = res.getIdentifier(name, "drawable", actualPack);
            }
        } else if (DEFAULT_PACK.equals(actualPack) && name.startsWith("pack_")) {
            id = res.getIdentifier("ic_" + name.substring("pack_".length()), "drawable", actualPack);
        }
        if (id == 0) {
            id = res.getIdentifier(name, "drawable", actualPack);
        }
        if (id == 0) {
            return null;
        }
        try {
            return res.getDrawableForDensity(id, iconDpi, null);
        } catch (Exception e) {
            Log.w(TAG, "Unable to load " + name + " from " + actualPack, e);
            return null;
        }
    }

    private boolean ensureLoaded(String pack) {
        if (pack.equals(mLoadedPack) && mPackResources != null) {
            return true;
        }
        mLoadedPack = "";
        mPackResources = null;
        mComponentMap.clear();
        mPackageMap.clear();

        try {
            Resources res = mContext.getPackageManager().getResourcesForApplication(pack);
            XmlPullParser parser = openAppFilter(res, pack);
            if (parser == null) {
                Log.w(TAG, "No appfilter.xml in " + pack);
                return false;
            }
            parseAppFilter(parser);
            if (DEFAULT_PACK.equals(pack)) {
                mPackageMap.putIfAbsent("org.fossify.gallery", "ic_gallery");
                mPackageMap.putIfAbsent("com.google.android.apps.weather.launcher", "ic_weather");
                mPackageMap.putIfAbsent("org.lineageos.aperture", "ic_camera");
            }
            mPackResources = res;
            mLoadedPack = pack;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to load icon pack " + pack, e);
            return false;
        }
    }

    @Nullable
    private static XmlPullParser openAppFilter(Resources res, String pack) throws Exception {
        int id = res.getIdentifier("appfilter", "xml", pack);
        if (id != 0) {
            return res.getXml(id);
        }
        try {
            InputStream in = res.getAssets().open("appfilter.xml");
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            return parser;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void parseAppFilter(XmlPullParser parser) throws Exception {
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG || !"item".equals(parser.getName())) {
                continue;
            }
            String component = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (TextUtils.isEmpty(component) || TextUtils.isEmpty(drawable)) {
                continue;
            }
            if (component.startsWith(COMPONENT_PREFIX) && component.endsWith("}")) {
                component = component.substring(COMPONENT_PREFIX.length(), component.length() - 1);
            }
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn == null) {
                continue;
            }
            mComponentMap.put(cn.flattenToString(), drawable);

            String pkg = cn.getPackageName();
            String existing = mPackageMap.get(pkg);
            if (existing == null) {
                mPackageMap.put(pkg, drawable);
            } else if (!existing.equals(drawable)) {
                mPackageMap.put(pkg, AMBIGUOUS);
            }
        }
    }
}
