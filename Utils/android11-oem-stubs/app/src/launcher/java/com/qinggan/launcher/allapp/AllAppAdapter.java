package com.qinggan.launcher.allapp;

import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Synthetic H97C full-screen All Apps adapter surface. */
public class AllAppAdapter {
    private final List<AppBean> mAppBeans = new ArrayList<>();

    public void onBindViewHolder(AppViewHolder holder, int position) {
        if (position >= 0 && position < mAppBeans.size()) {
            holder.itemView.setTag(mAppBeans.get(position));
        }
    }

    public void onBindViewHolder(AppViewHolder holder, int position, List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    public static class AppViewHolder {
        public final View itemView;
        private final View iconView;
        private final TextView nameView;

        public AppViewHolder(View itemView, View iconView, TextView nameView) {
            this.itemView = itemView;
            this.iconView = iconView;
            this.nameView = nameView;
        }
    }
}
