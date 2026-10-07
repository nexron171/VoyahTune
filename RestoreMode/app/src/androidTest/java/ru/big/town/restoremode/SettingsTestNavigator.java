package ru.big.town.restoremode;

import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.function.Predicate;

final class SettingsTestNavigator {
    private static final Instrumentation INSTRUMENTATION =
            InstrumentationRegistry.getInstrumentation();

    private SettingsTestNavigator() {}

    static View find(View root, Predicate<View> predicate) {
        if (root == null) {
            return null;
        }
        if (predicate.test(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static View reveal(AdvanceActivity activity, Predicate<View> predicate) {
        View[] result = {null};
        int[] itemCount = {0};
        INSTRUMENTATION.waitForIdleSync();
        INSTRUMENTATION.runOnMainSync(
                () -> {
                    SettingsList list = activity.findViewById(R.id.settingsList);
                    result[0] = find(list, predicate);
                    itemCount[0] = list.getAdapter().getItemCount();
                });
        for (int position = 0; result[0] == null && position < itemCount[0]; position++) {
            int target = position;
            INSTRUMENTATION.runOnMainSync(
                    () -> {
                        SettingsList list = activity.findViewById(R.id.settingsList);
                        ((LinearLayoutManager) list.getLayoutManager())
                                .scrollToPositionWithOffset(target, 0);
                    });
            INSTRUMENTATION.waitForIdleSync();
            INSTRUMENTATION.runOnMainSync(
                    () -> result[0] = find(activity.findViewById(R.id.settingsList), predicate));
        }
        if (result[0] == null) {
            throw new AssertionError("Control not found in settings section");
        }
        return result[0];
    }

    static View text(AdvanceActivity activity, String label) {
        return reveal(
                activity,
                view ->
                        view instanceof TextView
                                && label.contentEquals(((TextView) view).getText()));
    }

    static View described(AdvanceActivity activity, String description) {
        return reveal(
                activity,
                view ->
                        description.contentEquals(
                                view.getContentDescription() == null
                                        ? ""
                                        : view.getContentDescription()));
    }

    static void scrollTo(AdvanceActivity activity, String key) {
        INSTRUMENTATION.runOnMainSync(
                () -> ((SettingsList) activity.findViewById(R.id.settingsList)).scrollToKey(key));
        INSTRUMENTATION.waitForIdleSync();
    }
}
