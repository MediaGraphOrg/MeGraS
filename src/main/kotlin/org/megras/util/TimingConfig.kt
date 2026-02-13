package org.megras.util

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext

/**
 * Reads the timingEnabled property from log4j2.xml.
 * Set <Property name="timingEnabled">true</Property> in log4j2.xml to enable timing logs.
 */
object TimingConfig {
    val enabled: Boolean by lazy {
        try {
            val context = LogManager.getContext(false) as LoggerContext
            val value = context.configuration.strSubstitutor.replace("\${timingEnabled}")
            value.equals("true", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
