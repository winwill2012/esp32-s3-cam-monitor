package com.welinklab.esp32camcontroller.net

/**
 * Normalizes MJPEG/stream URLs for stable HTTP parsing.
 */
object StreamUrlUtils {

    private val schemeAuthorityPath = Regex("""^((?i)https?://)([^/?#]+)(.*)$""")

    /**
     * Trims URL and keeps host/port format parseable for HTTP clients.
     */
    fun normalizeStreamUrl(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return input

        val match = schemeAuthorityPath.find(input) ?: return input
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val remainder = match.groupValues[3]

        if (authority.startsWith("[")) {
            return "$scheme$authority$remainder"
        }

        var host = authority
        var port: String? = null
        val lastColon = authority.lastIndexOf(':')
        if (lastColon > 0) {
            val tail = authority.substring(lastColon + 1)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                host = authority.substring(0, lastColon)
                port = tail
            }
        }

        // After optional :port strip, any ':' left means IPv6 (DNS hostnames cannot contain ':').
        if (!host.contains(':')) {
            return input
        }

        val bracketed = if (port != null) "[$host]:$port" else "[$host]"
        return "$scheme$bracketed$remainder"
    }
}
