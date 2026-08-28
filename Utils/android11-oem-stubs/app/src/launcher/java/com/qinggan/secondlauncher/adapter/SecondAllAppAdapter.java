package com.qinggan.secondlauncher.adapter;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.qinggan.launcher.allapp.AppBean;

import java.util.ArrayList;
import java.util.List;

/** Synthetic optional passenger home-rail adapter surface from the OD launcher. */
public class SecondAllAppAdapter {
    private final List<AppBean> allAppList = new ArrayList<>();

    public void setAllAppList(List<AppBean> apps) {
        allAppList.clear();
        allAppList.addAll(apps);
        notifyDataSetChanged();
    }

    public void onBindViewHolder(ViewHolder holder, int position) {
        // Stub.
    }

    public void notifyDataSetChanged() {
        // Stub.
    }

    public static class ViewHolder {
        public final View itemView;
        private final ImageView iconView;
        private final TextView nameView;

        public ViewHolder(View itemView, ImageView iconView, TextView nameView) {
            this.itemView = itemView;
            this.iconView = iconView;
            this.nameView = nameView;
        }
    }
}
