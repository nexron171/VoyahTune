package ru.big.town.restoremode.settings.sections.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import ru.big.town.restoremode.AdvanceActivity;

/** Compatibility entry: voice settings now live inside the shared settings navigation. */
public class VoiceSettingsRedirectActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(
                new Intent(this, AdvanceActivity.class)
                        .putExtra(AdvanceActivity.EXTRA_SECTION, AdvanceActivity.SECTION_VOICE));
        finish();
    }
}
