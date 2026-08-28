package com.qinggan.launcher.allapp;

import android.view.View;

/** Synthetic H97C owner-click surface for All Apps on either physical display. */
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
