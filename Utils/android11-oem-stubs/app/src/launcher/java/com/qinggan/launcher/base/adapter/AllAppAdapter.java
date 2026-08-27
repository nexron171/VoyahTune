package com.qinggan.launcher.base.adapter;

import android.view.View;
import android.widget.TextView;

import com.qinggan.launcher.base.bean.AppBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic full-screen All Apps adapter surface. It deliberately has no mScreenId: the production
 * adapter does not own display routing; AllAppBarView does.
 */
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

    public void notifyDataSetChanged() {
        // Stub.
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
