package com.example.ai

import com.example.model.AppCommand
import java.util.Locale

object CommandParser {

    fun parse(text: String): AppCommand? {
        val cleanText = text.trim().lowercase(Locale.getDefault())
        if (cleanText.isEmpty()) return null

        // 1. Prime Contacts Commands (Close Friend, Meri Jaan, Second Contact, etc.)
        if (cleanText.contains("close friend ko call") || cleanText.contains("call my close friend") || cleanText.contains("call close friend")) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
        }
        if (cleanText.contains("message my love") || cleanText.contains("msg my love") || cleanText.contains("meri jaan ko message") || cleanText.contains("meri jaan ko msg")) {
            return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
        }
        if (cleanText.contains("call my second contact") || cleanText.contains("second contact ko call") || cleanText.contains("call my second friend")) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
        }

        // 2. Hardware Toggles: Flashlight
        if (cleanText.contains("flashlight on") || cleanText.contains("torch on") || cleanText.contains("flash on")) {
            return AppCommand(AppCommand.FLASHLIGHT_ON)
        }
        if (cleanText.contains("flashlight off") || cleanText.contains("torch off") || cleanText.contains("flash off")) {
            return AppCommand(AppCommand.FLASHLIGHT_OFF)
        }

        // 3. Hardware Toggles: Volume
        if (cleanText.contains("volume badhao") || cleanText.contains("volume up") || cleanText.contains("sound up")) {
            return AppCommand(AppCommand.VOLUME_UP)
        }
        if (cleanText.contains("volume kam") || cleanText.contains("volume down") || cleanText.contains("sound down") || cleanText.contains("volume ghathao")) {
            return AppCommand(AppCommand.VOLUME_DOWN)
        }

        // 4. Hardware Toggles: WiFi
        if (cleanText.contains("wifi on") || cleanText.contains("wi-fi on")) {
            return AppCommand(AppCommand.WIFI_ON)
        }
        if (cleanText.contains("wifi off") || cleanText.contains("wi-fi off")) {
            return AppCommand(AppCommand.WIFI_OFF)
        }

        // 5. Hardware Toggles: Bluetooth
        if (cleanText.contains("bluetooth on")) {
            return AppCommand(AppCommand.BLUETOOTH_ON)
        }
        if (cleanText.contains("bluetooth off")) {
            return AppCommand(AppCommand.BLUETOOTH_OFF)
        }

        // 6. Close App Commands
        if (cleanText.contains("band karo") || cleanText.contains("close app") || cleanText.contains("exit app") || cleanText.contains("stop whatsapp") || cleanText.contains("whatsapp band")) {
            return AppCommand(AppCommand.CLOSE_APP)
        }

        // 7. App Open Commands
        // Pattern matches: "open [App Name]" or "[App Name] kholo"
        val openAppPatterns = listOf(
            Regex("open\\s+([a-zA-Z0-9\\s]+)"),
            Regex("([a-zA-Z0-9\\s]+)\\s+kholo"),
            Regex("launch\\s+([a-zA-Z0-9\\s]+)")
        )
        for (pattern in openAppPatterns) {
            val match = pattern.find(cleanText)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                // Simple sanitary checks to avoid matching general conversation actions
                if (appName != "door" && appName != "gate" && appName != "heart" && appName != "eye") {
                    return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appName))
                }
            }
        }

        // 8. SMS Send Commands
        // Pattern: "sms bhejo [Name] ko" or "send sms to [Name]" or "message [Name]"
        val smsPatterns = listOf(
            Regex("sms\\s+bhejo\\s+([a-zA-Z\\s]+)\\s+ko"),
            Regex("send\\s+sms\\s+to\\s+([a-zA-Z\\s]+)"),
            Regex("message\\s+([a-zA-Z\\s]+)")
        )
        for (pattern in smsPatterns) {
            val match = pattern.find(cleanText)
            if (match != null) {
                val name = match.groupValues[1].trim()
                return AppCommand(AppCommand.SMS, mapOf("name" to name))
            }
        }

        // 9. Call Commands
        // Pattern: "call [Name]" or "[Name] ko call karo"
        val callPatterns = listOf(
            Regex("call\\s+([a-zA-Z0-9\\s+]+)"),
            Regex("([a-zA-Z0-9\\s+]+)\\s+ko\\s+call\\s+karo"),
            Regex("([a-zA-Z0-9\\s+]+)\\s+ko\\s+call\\s+lgao")
        )
        for (pattern in callPatterns) {
            val match = pattern.find(cleanText)
            if (match != null) {
                val operand = match.groupValues[1].trim()
                if (operand != "friend" && operand != "close friend" && operand != "second contact" && operand != "me") {
                    return AppCommand(AppCommand.CALL, mapOf("target" to operand))
                }
            }
        }

        // 10. WhatsApp Messaging Commands
        if (cleanText.contains("whatsapp message") || cleanText.contains("whatsapp karo") || cleanText.contains("whatsapp msg")) {
            val waPatterns = listOf(
                Regex("whatsapp\\s+karo\\s+([a-zA-Z\\s]+)\\s+ko"),
                Regex("whatsapp\\s+message\\s+to\\s+([a-zA-Z\\s]+)")
            )
            for (pattern in waPatterns) {
                val match = pattern.find(cleanText)
                if (match != null) {
                    val name = match.groupValues[1].trim()
                    return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("name" to name))
                }
            }
            return AppCommand(AppCommand.WHATSAPP_MSG)
        }

        return null
    }
}
