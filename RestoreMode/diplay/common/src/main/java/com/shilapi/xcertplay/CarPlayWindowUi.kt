package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The embedding app owns OEM safe bounds; the protocol host measures the resulting viewport. */
object CarPlayWindowUi {
    interface Policy {
        fun insets(activity: Activity, view: View, system: Insets): Insets
        fun authenticationError(activity: Activity): String
        fun connectionIcon(): Int = 0
        fun darkHostAppearance(): Boolean = false
    }

    private var policy: Policy? = null
    @JvmStatic fun install(policy: Policy?) { this.policy = policy }

    internal fun authenticationError(activity: Activity): String? = policy?.authenticationError(activity)
    internal fun connectionIcon(): Int = policy?.connectionIcon() ?: 0
    internal fun darkHostAppearance(): Boolean = policy?.darkHostAppearance() == true

    internal fun configureWindow(activity: Activity): Boolean {
        if (policy == null) return false
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(activity.window.decorView)
        return true
    }

    internal fun wrap(activity: Activity, content: View): View {
        val owner = policy ?: return content
        val outer = FrameLayout(activity)
        outer.addView(content, FrameLayout.LayoutParams(-1, -1))
        var system = Insets.NONE
        fun update() {
            val safe = owner.insets(activity, outer, system)
            if (outer.paddingLeft != safe.left || outer.paddingTop != safe.top ||
                outer.paddingRight != safe.right || outer.paddingBottom != safe.bottom) {
                outer.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
            system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            update()
            // The inner home/preparation screen must not reserve the same bars a second time.
            WindowInsetsCompat.CONSUMED
        }
        // Re-evaluate screen coordinates after window moves, resizes and first attachment.
        outer.viewTreeObserver.addOnGlobalLayoutListener { update() }
        outer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { ViewCompat.requestApplyInsets(view) }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        return outer
    }
}
