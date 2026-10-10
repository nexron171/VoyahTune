package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = -DEBOUNCE_MILLIS
    private var usbWindowId: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            usbWindowId = if (isSystemUsbWindow(event.packageName?.toString(), event.className?.toString())) {
                event.windowId
            } else null
        }
        if (usbWindowId != event.windowId) return
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (root.windowId != usbWindowId || root.packageName?.toString() !in SYSTEM_PACKAGES) return
            val texts = mutableListOf<String>()
            visit(root) { node ->
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
                false
            }
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            if (!isTargetPrompt(texts.joinToString(" "), appLabel)) return
            // Only the system USB dialog's optional default checkbox may be changed.
            visit(root) { node ->
                node.isCheckable && !node.isChecked && node.isEnabled &&
                    node.viewIdResourceName == "android:id/alwaysUse" &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val confirmed = visit(root) { node ->
                val label = node.text?.toString()?.trim()
                node.isEnabled && node.isClickable &&
                    (node.viewIdResourceName == "android:id/button1" || label in CONFIRM_LABELS) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (confirmed) {
                lastClickTime = now
                Log.i(TAG, "Successfully auto-confirmed DiPlay USB permission dialog")
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() { usbWindowId = null }

    private fun visit(node: AccessibilityNodeInfo, depth: Int = 0, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 32) return false
        if (action(node)) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (visit(child, depth + 1, action)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val DEBOUNCE_MILLIS = 800L
        private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")
        private val USB_ACTIVITIES = setOf(
            "com.android.systemui.usb.UsbPermissionActivity",
            "com.android.systemui.usb.UsbConfirmActivity",
        )
        private val CONFIRM_LABELS = setOf("确定", "允许", "OK", "Allow", "Confirm")

        internal fun isSystemUsbWindow(pkg: String?, className: String?): Boolean =
            pkg in SYSTEM_PACKAGES && className in USB_ACTIVITIES

        /**
         * The window is already known to be the system USB dialog, so the prompt only has to name
         * this app. Android 10+ words it "Allow DiPlay to access iPhone?" with no "USB", and Chinese
         * puts the name between letters ("要允许DiPlay访问iPhone吗？"), so only ASCII word characters
         * count as part of a longer name such as "FakeDiPlay".
         */
        internal fun isTargetPrompt(text: String, appLabel: String): Boolean =
            appLabel.isNotBlank() &&
                Regex("(?<![A-Za-z0-9_])${Regex.escape(appLabel)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)


        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
            val myShortService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myShortService, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        /** BYD's settings app does not answer the standard action but has its own screen (#409). */
        private val ACCESSIBILITY_SCREENS = listOf(
            ComponentName("com.byd.systemsettings", "com.byd.systemsettings.accessibility.AccessibilityMainActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
        )

        fun openSettings(context: Context): Boolean =
            (listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) + ACCESSIBILITY_SCREENS.map { Intent().setComponent(it) })
                .any { intent ->
                    runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                }
    }
}
