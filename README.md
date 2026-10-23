# Newly Chat

Native Android chat client for Claude, OpenAI, Gemini, and Mistral, built with
Kotlin and Jetpack Compose.

## Build

Open this project in Android Studio with Android SDK 35 installed, or run
`gradle :app:assembleDebug` with a compatible Gradle 8.9 installation.

## Use

Open **Providers** in the app to enter and save an API key. Keys are stored in
Android encrypted shared preferences. Select a configured provider from the
chat toolbar and send messages; chat history is stored locally with Room.

Each provider sends requests directly to its official API. API usage may incur
charges under the provider's account and terms.
