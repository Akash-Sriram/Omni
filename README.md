# Omni

**Omni** is a sleek, privacy-focused Android hub that brings the world's most powerful AI models into a single, unified interface.

Instead of installing a dozen different web wrappers or bloated apps, Omni gives you instant, tabbed access to the leading AI chat models in a clean, natively-optimized environment.

## Supported Models
- **ChatGPT** (OpenAI)
- **Claude** (Anthropic)
- **Gemini** (Google)
- **DeepSeek** (DeepSeek)

## Features
- 🚀 **Instant Switching**: All models are eagerly loaded in the background for zero-latency tab switching.
- 🔒 **Privacy-First**: Operates strictly in a "Restricted Mode." All analytics, tracking pixels, and non-essential third-party domains are hard-blocked at the WebView level.
- 📁 **Native File Support**: Seamlessly upload files and images using Android's modern Activity Result APIs.
- ⚡ **100% Kotlin**: Built entirely from the ground up using modern, null-safe Kotlin.
- 🎨 **Minimalist UI**: No clutter. Just a simple bottom navigation bar to switch between the models you need.

## Building from Source
Omni is built using the standard Android Gradle toolchain (AGP 8.5+).

```bash
git clone https://github.com/Akash-Sriram/Omni.git
cd Omni
./gradlew clean assembleDebug
```

## License
This app is licensed under the GPLv3. 
*(Originally forked and heavily rewritten from the legacy `gptAssist` project).*
