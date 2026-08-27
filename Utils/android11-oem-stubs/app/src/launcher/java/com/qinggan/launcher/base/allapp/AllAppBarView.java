package com.qinggan.launcher.base.allapp;

import android.view.View;

/** Synthetic owner-click surface for the full All Apps UI on either physical display. */
public class AllAppBarView implements View.OnClickListener {
    private int mScreenId;

    public AllAppBarView() {
        this(0);
    }

    public AllAppBarView(int screenId) {
        mScreenId = screenId;
    }

    @Override
    public void onClick(View view) {
        // Stub.
    }

    private void dismiss() {
        // Stub.
    }
}
