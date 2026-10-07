package ru.big.town.restoremode.settings.shell;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsHost;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.sections.can.CanCommandsViewModel;
import ru.big.town.restoremode.settings.state.SettingsApplyViewModel;
import ru.big.town.restoremode.trips.location.TripLocationPermission;

/** Settings shell: navigation, insets and shared Apply/Back actions. */
public class SettingsActivity extends AppCompatActivity implements SettingsHost {
    public static final String EXTRA_SECTION = "settingsSection";
    public static final int SECTION_VOICE = 7;
    public static final int SECTION_SCENARIOS = 8;
    private static final String PREF_SHOW_CUSTOM_COMMANDS = "showCustomCommands";

    private SharedPreferences preferences;
    private SettingsNavigationRail navigation;
    private SettingsHeaderView header;
    private SettingsSection currentSection = SettingsSection.MAIN;
    private CanCommandsViewModel commands;
    private SettingsApplyViewModel applyModel;
    private final SharedPreferences.OnSharedPreferenceChangeListener navigationPreferences =
            (preferences, key) -> {
                if (PREF_SHOW_CUSTOM_COMMANDS.equals(key)) {
                    navigation.showCustomCommands(preferences.getBoolean(key, false));
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_advance);
        applyWindowInsets();
        preferences = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
        commands = new ViewModelProvider(this).get(CanCommandsViewModel.class);
        String initialCommands = getIntent().getStringExtra("customCommand");
        commands.initialize(
                initialCommands != null
                        ? initialCommands
                        : preferences.getString("customCommand", ""),
                getIntent()
                        .getIntExtra(
                                "customCommandCount", preferences.getInt("customCommandCount", 1)));
        applyModel = new ViewModelProvider(this).get(SettingsApplyViewModel.class);
        navigation = findViewById(R.id.settingsNavigationRail);
        header = findViewById(R.id.settingsHeader);
        navigation.onSectionSelected(this::openSection);
        navigation.onBack(this::finishWithCustomCommands);
        navigation.showCustomCommands(preferences.getBoolean(PREF_SHOW_CUSTOM_COMMANDS, false));
        preferences.registerOnSharedPreferenceChangeListener(navigationPreferences);
        header.onApply(this::applySettings);
        applyModel.applying().observe(this, applying -> updateHeader());
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                finishWithCustomCommands();
                            }
                        });
        int initial =
                savedInstanceState == null
                        ? getIntent().getIntExtra(EXTRA_SECTION, 0)
                        : savedInstanceState.getInt(EXTRA_SECTION, 0);
        openSection(SettingsSection.fromIdentifier(initial));
    }

    @Override
    public void openSection(SettingsSection section) {
        FragmentManager manager = getSupportFragmentManager();
        if (manager.isStateSaved()) {
            return;
        }
        String tag = "settings:" + section.name();
        Fragment target = manager.findFragmentByTag(tag);
        if (target == null || target.isDetached()) {
            FragmentTransaction transaction = manager.beginTransaction().setReorderingAllowed(true);
            for (Fragment fragment : manager.getFragments()) {
                if (fragment instanceof SettingsSectionFragment && !fragment.isDetached()) {
                    transaction.detach(fragment);
                }
            }
            if (target == null) {
                transaction.add(R.id.settingsSectionContainer, section.createFragment(), tag);
            } else {
                transaction.attach(target);
            }
            transaction.commitNow();
        }
        currentSection = section;
        navigation.select(section);
        updateHeader();
    }

    private void updateHeader() {
        header.setTitle(currentSection.title, currentSection.category);
        header.setApplying(applyModel.isApplying(), currentSection.savesImmediately());
    }

    private void applySettings() {
        if (currentSection.savesImmediately()) {
            Toast.makeText(
                            this,
                            currentSection == SettingsSection.SCENARIOS
                                    ? "Сценарии сохранены"
                                    : "Настройки голосового управления сохранены",
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (commands.save(preferences)) {
            applyModel.apply();
        }
    }

    private void finishWithCustomCommands() {
        if (!commands.save(preferences)) {
            return;
        }
        setResult(
                RESULT_OK,
                new Intent()
                        .putExtra("customCommand", commands.text())
                        .putExtra("customCommandCount", commands.count()));
        finish();
    }

    // The OEM dock overlays content without reporting window insets.
    private void applyWindowInsets() {
        final int nativeDock = Math.round(getResources().getDisplayMetrics().density * 145f);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int top = bars.top;
                    if (top == 0) {
                        int resource =
                                getResources()
                                        .getIdentifier("status_bar_height", "dimen", "android");
                        if (resource > 0) {
                            top = getResources().getDimensionPixelSize(resource);
                        }
                    }
                    view.setPadding(nativeDock + bars.left, top, bars.right, bars.bottom);
                    return insets;
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt(EXTRA_SECTION, currentSection.identifier);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TripLocationPermission.refresh(this);
        TripLocationPermission.sync(this);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        TripLocationPermission.result(this, request);
    }

    @Override
    protected void onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(navigationPreferences);
        super.onDestroy();
    }
}
