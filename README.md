# Fluent

Fluent is a voice conversation partner for practising a foreign language. You speak, Claude answers out loud and keeps the conversation going like a real person, correcting one mistake at a time. Corrections are saved to a notebook you can review later.

Available languages: English (American, British), Spanish (Spain, Mexico), German, Italian, Portuguese (Brazil, Portugal) and Dutch. The interface and the explanations are in French.

## How it works

- **End word**: Fluent keeps listening until you say your end word, even if you pause. Each language has a default end word, which you can change in **Réglages** (Settings). If you leave it empty, your message is sent after a short silence.
- **Accent**: Claude receives the transcript along with the speech recognizer's other hypotheses. It works out what you meant and shows it under your message ("Compris : …"). It never corrects pronunciation.
- **Hands-free**: after each reply has been read aloud, Fluent starts listening again.
- **Voice commands**: ask Claude, in the language you are practising or in French, to repeat, to speak more slowly or faster, to change the subject or to pause.

## Car mode

Turn it on in **Réglages**. The display gets bigger, replies get shorter and Fluent keeps listening longer when you are silent. The screen stays on during the conversation. After a network drop, Fluent retries on its own and tells you out loud, in French.

Keep Chrome in the foreground: if the screen locks or you switch apps, Android turns the microphone off.

## Install on Android

1. Open the published page (GitHub Pages) in **Chrome**. The microphone only works on an HTTPS page.
2. In **Réglages**, paste an Anthropic API key (console.anthropic.com). It stays in the browser, and calls go straight from the browser to the Anthropic API.
3. Pick the language, your level and a voice, then tap the microphone.
4. Use the ⋮ menu → "Add to Home screen" to get an app-like icon.

Chrome on Android plays a short beep each time listening restarts: that is the system's behaviour.

## Development

`app.html` is the only source file. After editing it, rebuild the standalone page:

```sh
./build.sh
```
