package com.khkt.seniorvoice.executor

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tầng 4 (Tùy chọn nâng cao): Dịch vụ Hỗ trợ Tiếp cận (AccessibilityService)
 * Giúp tự động điền hoặc nhấn nút Gửi trong ứng dụng nhắn tin sau khi ông bà đã xác nhận bằng giọng nói.
 */
class SeniorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var pendingAutoMessageText: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val messageToFill = pendingAutoMessageText ?: return
        val rootNode = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString().orEmpty()

        // Chỉ hỗ trợ điền tin nhắn khi mở Zalo hoặc ứng dụng SMS sau bước xác nhận
        if (pkg.contains("zalo") || pkg.contains("messaging") || pkg.contains("mms")) {
            fillEditableNodeIfPresent(rootNode, messageToFill)
        }
    }

    private fun fillEditableNodeIfPresent(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val filled = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (filled) {
                pendingAutoMessageText = null
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (fillEditableNodeIfPresent(child, text)) return true
        }
        return false
    }

    override fun onInterrupt() {
        pendingAutoMessageText = null
    }
}
