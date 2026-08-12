package com.multiassist.app

enum class Provider(
    val label: String,
    val url: String,
    val iconRes: Int,
    private val ownDomains: List<String>
) {
    CHATGPT(
        "ChatGPT", "https://chatgpt.com/", R.drawable.ic_chatgpt,
        listOf(
            "chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com",
            "oaistatsig.com", "cdn.auth0.com", "auth.openai.com",
            "workos.com", "workoscdn.com", "imgix.net",
            "intercom.io", "intercomcdn.com",
            "challenges.cloudflare.com", "cloudflare.com"
        )
    ),
    CLAUDE(
        "Claude", "https://claude.ai/", R.drawable.ic_claude,
        listOf(
            "claude.ai", "anthropic.com",
            "statsig.anthropic.com", "featuregates.org",
            "datadoghq.com", // Datadog RUM monitoring (Claude uses heavily)
            "googletagmanager.com", // Google Tag Manager
            "cloudflareinsights.com", "challenges.cloudflare.com"
        )
    ),
    GEMINI(
        "Gemini", "https://gemini.google.com/", R.drawable.ic_gemini,
        listOf(
            "doubleclick.net", "googletagmanager.com", "google-analytics.com",
            "youtube.com", "ytimg.com"
        )
    ),
    DEEPSEEK(
        "DeepSeek", "https://chat.deepseek.com/", R.drawable.ic_deepseek,
        listOf(
            "deepseek.com", // covers chat, api, platform, cdn subdomains
            "cloudfront.net", // Amazon CloudFront CDN used by DeepSeek
            "challenges.cloudflare.com"
        )
    ),
    KIMI(
        "Kimi", "https://kimi.moonshot.cn/", R.drawable.ic_kimi,
        listOf(
            "kimi.moonshot.cn", "moonshot.cn", "moonshotai.com"
        )
    );

    /** Returns true if the given host is allowed for this provider in restricted mode. */
    fun isAllowed(host: String?): Boolean {
        if (host == null) return false
        if (SHARED_DOMAINS.any { host.endsWith(it) }) return true
        if (ownDomains.any { host.endsWith(it) }) return true
        return false
    }

    companion object {
        // Shared Google OAuth + common security domains applied to ALL providers
        val SHARED_DOMAINS = listOf(
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
            "youtube.com", // accounts.youtube.com needed in Google OAuth account chooser
            "hcaptcha.com" // Claude + DeepSeek both use hCaptcha for login verification
        )
    }
}
