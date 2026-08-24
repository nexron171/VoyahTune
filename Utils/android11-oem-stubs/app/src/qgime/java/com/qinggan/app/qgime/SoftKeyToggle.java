package com.qinggan.app.qgime;

import android.graphics.drawable.Drawable;

/** Minimal synthetic toggle key and reflected fields used during hook setup. */
public class SoftKeyToggle extends SoftKey {
    public ToggleState mToggleState;

    public ToggleState createToggleState() {
        return new ToggleState();
    }

    public void setToggleStates(ToggleState firstState) {
        mToggleState = firstState;
    }

    public String getKeyLabel() {
        return mKeyLabel == null ? null : mKeyLabel.toString();
    }

    public ToggleState getToggleState() {
        return mToggleState;
    }

    public static class ToggleState {
        public int mKeyCode;
        public CharSequence mKeyLabel;
        public Drawable mKeyIcon;
        public ToggleState mNextState;

        public void setStateId(int stateId) {
            // Stub.
        }

        public void setStateFlags(boolean repeat, boolean balloon) {
            // Stub.
        }
    }
}
