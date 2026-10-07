package ru.big.town.restoremode.settings.shell;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

public final class SettingsHeaderView extends FrameLayout {
    private final TextView title;
    private final TextView category;
    private final Button applyButton;
    private final ProgressBar progress;

    public SettingsHeaderView(Context context, AttributeSet attributes) {
        super(context, attributes);
        LayoutInflater.from(context).inflate(R.layout.view_settings_header, this, true);
        title = findViewById(R.id.sectionTitle);
        category = findViewById(R.id.settingsEyebrow);
        applyButton = findViewById(R.id.buttonApplyAdvance);
        progress = findViewById(R.id.applyProgressAdvance);
        SettingsDesign.styleTree(this);
        getChildAt(0).setBackground(new SettingsHeaderDrawable(this));
        setElevation(SettingsDesign.dp(this, 8));
        setOutlineProvider(null);
    }

    public void setTitle(String title, String category) {
        this.title.setText(title);
        this.category.setText(category);
    }

    public void onApply(Runnable listener) {
        applyButton.setOnClickListener(view -> listener.run());
    }

    public void setApplying(boolean applying, boolean savesImmediately) {
        applyButton.setEnabled(savesImmediately || !applying);
        progress.setVisibility(applying && !savesImmediately ? View.VISIBLE : View.GONE);
    }
}
