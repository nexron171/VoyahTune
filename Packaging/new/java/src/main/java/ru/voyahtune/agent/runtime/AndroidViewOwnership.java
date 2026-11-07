package ru.voyahtune.agent.runtime;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;

public final class AndroidViewOwnership implements ViewOwnership.Access {
    @Override public Object read(Object owner, String property) throws Exception {
        View view = (View) owner;
        switch (property) {
            case "onClick":
                Object info = OemAccess.read(view, "mListenerInfo");
                return info == null ? null : OemAccess.read(info, "mOnClickListener");
            case "description": return view.getContentDescription();
            case "topMargin": return ((ViewGroup.MarginLayoutParams) parameters(view)).topMargin;
            case "background": return view.getBackground();
            case "drawable": return ((ImageView) view).getDrawable();
            case "text": return ((TextView) view).getText();
            case "visibility": return view.getVisibility();
            case "height": return parameters(view).height;
            case "checked": return ((Checkable) view).isChecked();
            case "longClickable": return view.isLongClickable();
            case "longClick":
                Object listeners = OemAccess.read(view, "mListenerInfo");
                return listeners == null ? null : OemAccess.read(listeners, "mOnLongClickListener");
            default: throw new IllegalArgumentException("Unknown view property: " + property);
        }
    }

    @Override public void write(Object owner, String property, Object value) {
        View view = (View) owner;
        switch (property) {
            case "onClick": view.setOnClickListener((View.OnClickListener) value); break;
            case "description": view.setContentDescription((CharSequence) value); break;
            case "topMargin":
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) parameters(view);
                if (margins.topMargin != (Integer) value) {
                    // The OEM shares one RadioGroup.LayoutParams between several buttons.
                    android.widget.RadioGroup.LayoutParams copy = new android.widget.RadioGroup.LayoutParams(margins);
                    copy.topMargin = (Integer) value;
                    view.setLayoutParams(copy);
                }
                break;
            case "background": view.setBackground((Drawable) value); break;
            case "drawable": ((ImageView) view).setImageDrawable((Drawable) value); break;
            case "text": ((TextView) view).setText((CharSequence) value); break;
            case "visibility": view.setVisibility((Integer) value); break;
            case "height":
                ViewGroup.LayoutParams parameters = parameters(view);
                parameters.height = (Integer) value;
                view.setLayoutParams(parameters);
                break;
            case "checked": ((Checkable) view).setChecked((Boolean) value); break;
            case "longClickable": view.setLongClickable((Boolean) value); break;
            case "longClick": view.setOnLongClickListener((View.OnLongClickListener) value); break;
            default: throw new IllegalArgumentException("Unknown view property: " + property);
        }
    }

    private static ViewGroup.LayoutParams parameters(View view) {
        ViewGroup.LayoutParams parameters = view.getLayoutParams();
        if (parameters == null) throw new IllegalStateException("View layout parameters unavailable");
        return parameters;
    }

    @Override public AutoCloseable retain(Object owner, Object state) {
        View view = (View) owner;
        View.OnAttachStateChangeListener anchor = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { java.lang.ref.Reference.reachabilityFence(state); }
            @Override public void onViewDetachedFromWindow(View view) { java.lang.ref.Reference.reachabilityFence(state); }
        };
        view.addOnAttachStateChangeListener(anchor);
        return () -> view.removeOnAttachStateChangeListener(anchor);
    }
}
