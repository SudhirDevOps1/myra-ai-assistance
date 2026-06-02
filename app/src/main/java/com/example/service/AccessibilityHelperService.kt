package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityHelper"
        var instance: AccessibilityHelperService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${AccessibilityHelperService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            return enabledServices.contains(expectedComponentName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility helper service connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No action needed for events, we only perform directed actions manually
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- High-level actions ---

    fun closeCurrentApp(): Boolean {
        Log.d(TAG, "Performing GLOBAL_ACTION_HOME")
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        Log.d(TAG, "Performing GLOBAL_ACTION_BACK")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun clickOnText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) {
            Log.d(TAG, "No nodes containing text '$text' was found.")
            return false
        }
        
        for (node in nodes) {
            if (performClick(node)) {
                Log.d(TAG, "Clicked node containing text '$text'")
                return true
            }
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var tempNode = node
        while (tempNode != null) {
            if (tempNode.isClickable) {
                return tempNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            tempNode = tempNode.parent
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val editTexts = findEditableNodes(rootNode)
        if (editTexts.isEmpty()) {
            Log.d(TAG, "No editable edit texts found for typing text.")
            return false
        }

        // Action the first edit-text found as input focus target
        val targetNode = editTexts[0]
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "Setted text output: $success")
        return success
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val editableList = mutableListOf<AccessibilityNodeInfo>()
        if (node.isEditable) {
            editableList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            editableList.addAll(findEditableNodes(child))
        }
        return editableList
    }

    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return performScroll(rootNode, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return performScroll(rootNode, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun performScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            return node.performAction(action)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScroll(child, action)) {
                return true
            }
        }
        return false
    }
}
