package ru.big.town.restoremode.settings.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.state.SettingsSectionViewModel;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns only the visible row trees. Section data outlives its View through a ViewModel. */
public abstract class SettingsSectionFragment extends Fragment {
    protected SharedPreferences preferences;
    protected SettingsList settingsList;
    protected View bindingRoot;
    private SettingsSectionViewModel stateModel;
    private final Map<Integer, SettingsList.Row> staticRows = new HashMap<>();

    public abstract SettingsSection section();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences =
                requireContext().getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        stateModel = new ViewModelProvider(this).get(SettingsSectionViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        return inflater.inflate(R.layout.fragment_settings_section, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settingsList = (SettingsList) view;
        settingsList.setSaveFromParentEnabled(false);
        settingsList.setTag(section());
        Bundle state = stateModel.state();
        settingsList.restoreDrafts(state.getBundle("drafts"));
        settingsList.onRelease(this::releaseSettingsRow);
        settingsList.onVisibilityChanged(this::scheduleVisibilityUpdate);
        onSectionViewCreated(state);
        showRows(false);
        Parcelable scroll = state.getParcelable("scroll");
        if (scroll != null) {
            settingsList.getLayoutManager().onRestoreInstanceState(scroll);
        }
    }

    protected void onSectionViewCreated(Bundle state) {}

    protected void saveSectionState(Bundle state) {}

    protected void onSectionViewDestroyed() {}

    protected void onRowsVisibilityChanged() {}

    protected void releaseSettingsRow(View row) {}

    protected void bindSettingsRow() {}

    protected void addDynamicRows(
            List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        throw new IllegalArgumentException("Unsupported content in " + section() + ": " + kind);
    }

    protected View decorateSettingsRow(int layout, View row) {
        return row;
    }

    protected boolean cacheLayout(int layout) {
        return true;
    }

    protected void showRows(boolean resetScroll) {
        List<SettingsList.Row> rows = new ArrayList<>();
        for (SettingsSectionLayouts.Item item : SettingsSectionLayouts.forSection(section())) {
            if (item.layoutResource == 0) {
                addDynamicRows(rows, item.dynamicContent);
            } else if (!cacheLayout(item.layoutResource)) {
                rows.add(layoutRow(item.layoutResource));
            } else {
                rows.add(staticRows.computeIfAbsent(item.layoutResource, this::layoutRow));
            }
        }
        settingsList.submit(rows, resetScroll);
    }

    private SettingsList.Row layoutRow(int resource) {
        return new SettingsList.Row(
                "layout:" + resource, parent -> inflateSettingsRow(resource, parent));
    }

    private View inflateSettingsRow(int layout, ViewGroup parent) {
        View row = getLayoutInflater().inflate(layout, parent, false);
        bindingRoot = row;
        try {
            bindSettingsRow();
            SettingsDesign.bindRow(requireActivity(), row);
        } finally {
            bindingRoot = null;
        }
        return decorateSettingsRow(layout, row);
    }

    protected final <T extends View> T settingView(int id) {
        View root = bindingRoot != null ? bindingRoot : getView();
        return root == null ? null : root.findViewById(id);
    }

    protected final void bindClick(int id, View.OnClickListener listener) {
        View view = settingView(id);
        if (view != null) {
            view.setOnClickListener(listener);
        }
    }

    protected final void refreshSettingsRows() {
        SettingsList viewport = settingsList;
        if (viewport != null) {
            viewport.post(
                    () -> {
                        if (settingsList == viewport) {
                            showRows(false);
                        }
                    });
        }
    }

    protected final void openSection(SettingsSection section) {
        ((SettingsHost) requireActivity()).openSection(section);
    }

    protected final void scheduleVisibilityUpdate() {
        SettingsList viewport = settingsList;
        if (viewport != null) {
            viewport.post(
                    () -> {
                        if (settingsList == viewport) {
                            onRowsVisibilityChanged();
                        }
                    });
        }
    }

    private void captureState() {
        if (settingsList == null) {
            return;
        }
        Bundle state = stateModel.state();
        state.putBundle("drafts", settingsList.saveDrafts());
        state.putParcelable("scroll", settingsList.getLayoutManager().onSaveInstanceState());
        saveSectionState(state);
        stateModel.save(state);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        captureState();
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        captureState();
        settingsList.onVisibilityChanged(() -> {});
        settingsList.setAdapter(null);
        onSectionViewDestroyed();
        settingsList = null;
        bindingRoot = null;
        staticRows.clear();
        super.onDestroyView();
    }

    protected static boolean isDescendant(View root, View child) {
        if (child == null) {
            return false;
        }
        for (android.view.ViewParent parent = child.getParent();
                parent instanceof View;
                parent = parent.getParent()) {
            if (parent == root) {
                return true;
            }
        }
        return child == root;
    }
}
