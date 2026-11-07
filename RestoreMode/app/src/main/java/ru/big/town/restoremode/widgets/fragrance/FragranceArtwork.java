package ru.big.town.restoremode.widgets.fragrance;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.view.Gravity;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class FragranceArtwork {
    static final String VEHICLE = "com.qinggan.app.vehicle";
    static final Uri SETTINGS = Uri.parse("content://qinggan.settings/global");
    static final String[] DEFAULT_NAMES = {"Свежесть бамбука", "Пионовая красота", "Утренняя роса"};
    private static final String[] BACKGROUNDS = {"zhuyu", "mudan", "shuigan"};
    private static final String[] ICONS = {"zhuzi", "mudan", "shuigan"};
    private static final int[] PANORAMAS = {2, 3, 1};

    private final Map<String, Bitmap> images = new HashMap<>();
    private final Source launcher;
    private final Source vehicle;
    private final boolean simple;
    final String[] names = DEFAULT_NAMES.clone();

    private FragranceArtwork(Context context) {
        PackageManager packages = context.getPackageManager();
        String theme = property("persist.sys.qg.currenttheme", "default");
        File skin = null;
        if ("simple".equals(theme) || "golden".equals(theme)) {
            skin =
                    new File(
                            property("persist.sys.qg.default.theme_path", "/resc/defaulttheme/"),
                            theme);
        }
        simple = "simple".equals(theme);
        launcher =
                new Source(
                        packages, "com.qinggan.app.launcher", skin, "Launcher-release-signed.apk");
        vehicle = new Source(packages, VEHICLE, skin, "VehicleAir-release-signed.apk");
    }

    static FragranceArtwork load(
            Context context,
            FragranceWidgetLayout layout,
            int width,
            int height,
            BooleanSupplier current) {
        FragranceArtwork art = new FragranceArtwork(context);
        if (layout.expanded()) width = Math.max(width, Math.round(height * (1920f / 720)));
        for (int scent = 1; scent <= 3; scent++) {
            if (!current.getAsBoolean()) return null;
            art.names[scent - 1] = art.name(context, scent);
            for (boolean on : new boolean[] {false, true}) {
                String background = background(layout, scent, on);
                art.load(
                        layout.panoramic() ? art.vehicle : art.launcher,
                        background,
                        Math.min(width, 960),
                        Math.min(height, 450));
                if (layout != FragranceWidgetLayout.MINI) {
                    art.load(art.launcher, icon(scent, on), 120, 120);
                }
            }
        }
        if (layout.expanded()) {
            for (String name :
                    new String[] {
                        "fragrance_ic_switch_on",
                        "fragrance_ic_switch_off",
                        "fragrance_newcontrol_bgenable",
                        "fragrance_newcontrol_bgdisable",
                        "fragrance_newtab_sel",
                        "fragrance_newtab_disable"
                    }) {
                art.load(art.vehicle, name, 500, 80);
            }
        }
        return art;
    }

    static String background(FragranceWidgetLayout layout, int scent, boolean on) {
        return layout.panoramic()
                ? "fragrance_newbg_taste_" + PANORAMAS[scent - 1] + (on ? "_on" : "_off")
                : "fragrance_card_bg_" + BACKGROUNDS[scent - 1] + (on ? "" : "_off");
    }

    static String icon(int scent, boolean on) {
        return "fragrance_icon_" + ICONS[scent - 1] + (on ? "_on" : "_off");
    }

    private void load(Source source, String name, int width, int height) {
        images.put(name, source.bitmap(name, width, height));
    }

    BitmapDrawable drawable(Resources resources, String name) {
        Bitmap bitmap = images.get(name);
        if (bitmap == null) return null;
        BitmapDrawable drawable = new BitmapDrawable(resources, bitmap);
        drawable.setFilterBitmap(true);
        drawable.setGravity(Gravity.FILL);
        return drawable;
    }

    AnimationDrawable animation(
            Resources resources,
            FragranceWidgetLayout layout,
            int width,
            int height,
            BooleanSupplier current) {
        boolean panoramic = layout.panoramic();
        String prefix =
                simple ? "fragrance_openanimation_simple_" : "fragrance_openanimation_default_";
        AnimationDrawable animation = new AnimationDrawable();
        for (int frame = 0; frame < (panoramic ? 51 : 28); frame++) {
            if (!current.getAsBoolean()) return null;
            String name =
                    panoramic
                            ? prefix + frame
                            : String.format(Locale.ROOT, "fragrance_anim_%02d", frame * 2);
            Bitmap bitmap =
                    (panoramic ? vehicle : launcher)
                            .bitmap(name, Math.min(width, 200), Math.min(height, 220));
            if (bitmap == null) return null;
            animation.addFrame(new BitmapDrawable(resources, bitmap), panoramic ? 40 : 120);
        }
        animation.setOneShot(false);
        return animation;
    }

    private String name(Context context, int scent) {
        try (Cursor row =
                context.getContentResolver()
                        .query(
                                SETTINGS,
                                new String[] {"value"},
                                "name=?",
                                new String[] {"fg_name_0" + scent},
                                null)) {
            if (row != null && row.moveToFirst()) {
                String custom = row.getString(0);
                if (custom != null && !custom.trim().isEmpty()) return custom;
            }
        } catch (RuntimeException unavailable) {
            // The settings provider is optional on other head units.
        }
        String name = "fragrance_card_text_" + BACKGROUNDS[scent - 1];
        return launcher.string(name, DEFAULT_NAMES[scent - 1]);
    }

    private static String property(String key, String fallback) {
        try {
            return (String)
                    Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class, String.class)
                            .invoke(null, key, fallback);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return fallback;
        }
    }

    private static final class Source {
        private final String packageName;
        private Resources base;
        private Resources skin;
        private String skinPackage;

        Source(PackageManager packages, String packageName, File folder, String archive) {
            this.packageName = packageName;
            try {
                base = packages.getResourcesForApplication(packageName);
                if (folder == null) return;
                File apk = new File(folder, archive);
                if (!apk.isFile()) return;
                PackageInfo info = packages.getPackageArchiveInfo(apk.getPath(), 0);
                if (info == null || info.applicationInfo == null) return;
                info.applicationInfo.sourceDir = apk.getPath();
                info.applicationInfo.publicSourceDir = apk.getPath();
                skinPackage = info.packageName;
                skin = packages.getResourcesForApplication(info.applicationInfo);
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // Use the installed resources when optional theme assets cannot be opened.
            }
        }

        String string(String name, String fallback) {
            if (base == null) return fallback;
            int id = base.getIdentifier(name, "string", packageName);
            return id == 0 ? fallback : base.getString(id);
        }

        Bitmap bitmap(String name, int width, int height) {
            Bitmap themed = decode(skin, skinPackage, name, width, height);
            return themed != null ? themed : decode(base, packageName, name, width, height);
        }

        private static Bitmap decode(
                Resources resources, String pkg, String name, int width, int height) {
            if (resources == null) return null;
            int id = resources.getIdentifier(name, "drawable", pkg);
            if (id == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(resources, id, options);
            int sample = 1;
            while (options.outWidth / sample > 1.5f * Math.max(1, width)
                    || options.outHeight / sample > 1.5f * Math.max(1, height)) {
                sample *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            return BitmapFactory.decodeResource(resources, id, options);
        }
    }
}
