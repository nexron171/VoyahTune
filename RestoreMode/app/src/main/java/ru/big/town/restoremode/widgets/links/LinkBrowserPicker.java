package ru.big.town.restoremode.widgets.links;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LinkBrowserPicker {
    private final List<String> packages = new ArrayList<>();
    private final Spinner spinner;

    public LinkBrowserPicker(Context context, Spinner spinner, String selectedPackage) {
        this.spinner = spinner;
        List<String> labels = new ArrayList<>();
        packages.add("");
        labels.add("Штатный WebView (внутри VoyahTune)");
        PackageManager manager = context.getPackageManager();
        List<ResolveInfo> browsers = new ArrayList<>();
        for (String scheme : new String[] {"https", "http"}) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://example.com"));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            browsers.addAll(
                    manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY));
        }
        browsers.sort(new ResolveInfo.DisplayNameComparator(manager));
        Set<String> seen = new HashSet<>();
        for (ResolveInfo browser : browsers) {
            if (browser.activityInfo == null
                    || !browser.activityInfo.exported
                    || !browser.activityInfo.enabled
                    || !browser.activityInfo.applicationInfo.enabled) {
                continue;
            }
            String packageName = browser.activityInfo.packageName;
            if (packageName.equals(context.getPackageName()) || !seen.add(packageName)) {
                continue;
            }
            packages.add(packageName);
            labels.add(browser.loadLabel(manager).toString());
        }
        if (!selectedPackage.isEmpty() && !packages.contains(selectedPackage)) {
            packages.add(selectedPackage);
            labels.add("Недоступен: " + selectedPackage);
        }
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, packages.indexOf(selectedPackage)));
    }

    public String selectedPackage() {
        return packages.get(spinner.getSelectedItemPosition());
    }

    public static String label(Context context, String packageName) {
        if (packageName.isEmpty()) {
            return "WebView";
        }
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0))
                    .toString();
        } catch (PackageManager.NameNotFoundException exception) {
            return "Недоступен: " + packageName;
        }
    }
}
