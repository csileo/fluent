# Fluent

Fluent is a voice conversation partner for practising a foreign language. You speak, Claude answers out loud and keeps the conversation going like a real person, correcting one mistake at a time. Corrections are saved to a notebook you can review later.

Available languages: English (American, British), Spanish (Spain, Mexico), German, Italian, Portuguese (Brazil, Portugal) and Dutch. The interface is available in English and French. Explanations and Fluent's spoken messages use your native language (English or French), set in **Settings**.

## How it works

- **End word**: Fluent keeps listening until you say your end word, even if you pause. Each language has a default end word, which you can change in **Settings**. If you leave it empty, your message is sent after a short silence.
- **Accent**: Claude receives the transcript along with the speech recognizer's other hypotheses. It works out what you meant and shows it under your message ("Understood: …"). It never corrects pronunciation.
- **Hands-free**: after each reply has been read aloud, Fluent starts listening again.
- **Voice commands**: ask Claude, in the language you are practising or in your native language, to repeat, to speak more slowly or faster, to change the subject or to pause.

## Car mode

Turn it on in **Settings**. The display gets bigger, replies get shorter and Fluent keeps listening longer when you are silent. The screen stays on during the conversation. After a network drop, Fluent retries on its own and tells you out loud, in your native language.

Keep Chrome in the foreground: if the screen locks or you switch apps, Android turns the microphone off.

## Android app (recommended in the car)

The Android app wraps the same interface and adds what a web page can't do:

- **Bluetooth call mode**: during a conversation, the phone switches Bluetooth to call mode, so Fluent uses the car's microphone and speakers like a phone call (phone-call sound quality). Turn it off in **Settings** if you prefer.
- **Native speech recognition and voices**, without Chrome's restarts.
- A **foreground service** keeps the conversation running, with a notification while it lasts.

Install:

1. On the phone, open `https://github.com/csileo/fluent/releases/latest/download/fluent.apk` (sign in to GitHub if the repository is private) and open the downloaded file.
2. Allow installing apps from this source when Android asks.
3. Open Fluent, allow the microphone, nearby devices (Bluetooth) and notifications, then paste your Anthropic API key in **Settings**.

Each push to `master` builds a new APK (GitHub Actions) that installs over the previous one. To build it yourself: `./build.sh && gradle -p android assembleDebug` with the Android SDK installed.

## Install on Android (web version)

1. Open the published page (GitHub Pages) in **Chrome**. The microphone only works on an HTTPS page.
2. In **Settings**, paste an Anthropic API key (console.anthropic.com). It stays in the browser, and calls go straight from the browser to the Anthropic API.
3. Pick the language, your level and a voice, then tap the microphone.
4. Use the ⋮ menu → "Add to Home screen" to get an app-like icon.

Chrome on Android plays a short beep each time listening restarts: that is the system's behaviour.

## Development

`app.html` is the only source file. After editing it, rebuild the standalone page:

```sh
./build.sh
```
