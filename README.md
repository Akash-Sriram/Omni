# Omni

**Omni** is a sleek, privacy-focused Android hub that brings the world's most powerful AI models into a single, unified interface.

Instead of installing a dozen different web wrappers or bloated apps, Omni gives you instant, tabbed access to the leading AI chat models in a clean, natively-optimized environment.

## Supported Models
- **ChatGPT** (OpenAI)
- **Claude** (Anthropic)
- **Gemini** (Google)
- **DeepSeek** (DeepSeek)
- **Kimi** (Moonshot AI)

## Features
- 🚀 **Lightning Fast Cold Starts**: Implements lazy-loading. Only your default model is loaded on startup, keeping CPU and network usage minimal until you actually tap other tabs.
- 🔒 **Privacy-First**: Operates strictly in a "Restricted Mode." All analytics, tracking pixels, and non-essential third-party domains are hard-blocked at the WebView level.
- ⚙️ **Customizable**: A dedicated settings panel allows you to hide models you don't use, select your default startup tab, and force Dark or Light theme globally.
- 🔋 **Background Keep-Alive**: Toggleable foreground service prevents Android's aggressive battery managers from killing your chat sessions when you switch apps.
- 🛑 **Danger Zone**: A single button to nuke all WebStorage, clear all cookies, and instantly log you out of all AI services simultaneously.
- 📁 **Native File Support**: Seamlessly upload files and images using Android's modern Activity Result APIs.
- ⚡ **100% Kotlin**: Built entirely from the ground up using modern, null-safe Kotlin.
- 🎨 **Minimalist UI**: No clutter. Just a simple bottom navigation bar to switch between the models you need.

## License
This app is licensed under the GPLv3. 
*(Originally forked and heavily rewritten from the legacy `gptAssist` project).*
