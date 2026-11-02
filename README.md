# Fluent

Fluent is a voice conversation partner for practising a foreign language. You speak, Claude answers out loud and keeps the conversation going like a real person, correcting one mistake at a time. Corrections are saved to a notebook you can review later.

Available languages: French, English (American, British), Spanish (Spain, Mexico), German, Italian, Portuguese (Brazil, Portugal) and Dutch. The interface is available in English and French. Explanations and Fluent's spoken messages use your native language (English or French), set in **Settings**.

## How it works

- **End word**: Fluent keeps listening until you say your end word, even if you pause. Each language has a default end word, which you can change in **Settings**. If you leave it empty, your message is sent after a short silence.
- **Accent**: Claude works out what you meant from the transcript and shows it under your message ("Understood: …"). It never corrects pronunciation. **Settings → Recognition hypotheses** can also send it the speech recognizer's other versions of your sentence (none by default, the best one, or up to 3 per segment); more hypotheses help with misheard words but cost more.
- **Prompt caching** (on by default): the instructions and the history are cached from one sentence to the next, so they are read back at about 5% of the normal price and answers come faster. What changes every time (current settings, conversation list, mistake tally) is sent after the cached part. Diagnostics show the tokens and estimated cost of each request, with a running total per conversation.
- **Hands-free**: after each reply has been read aloud, Fluent starts listening again.
- **Prompts**: **Settings** holds several prompts. Two built-in ones can always be restored: *Language practice* (Claude chats like a friend, corrects important mistakes right away, lets small ones go and brings them up once the same kind comes back 3 times) and *Free conversation* (a spoken chat with Claude, as in writing, without corrections). You can edit them or create your own, with placeholders such as `{language}` or `{level}`.
- **Conversations**: like in the Claude app, **New conversation** keeps the previous ones; the **Chats** list reopens, renames or deletes them. Each conversation remembers its prompt, and its history is sent to Claude with every message, so it remembers the discussion, also after a restart.
- **Everything by voice**: just ask, in your own words, to change any setting (language, voice speed, end word, car mode…), to create, edit or switch prompts, or to start, open, rename or delete a conversation. When a change needs the microphone restarted, Fluent does it on its own. Only the API key cannot be changed by voice.
- **Saved**: settings, notebook and all conversations are kept when you close the app.
- **Confirmation sound**: a short, soft two-note sound tells you your sentence was received and Claude is thinking (can be turned off in **Settings**).
- **Voice commands**: ask Claude, in the language you are practising or in your native language, to repeat, to speak more slowly or faster, to change the subject or to pause.

## Car mode

Turn it on in **Settings**. The display gets bigger, replies get shorter and Fluent keeps listening longer when you are silent. The screen stays on during the conversation. After a network drop, Fluent retries on its own and tells you out loud, in your native language.

Keep Chrome in the foreground: if the screen locks or you switch apps, Android turns the microphone off.

## Android app (recommended in the car)

The Android app wraps the same interface and adds what a web page can't do:

- **Bluetooth call mode**: during a conversation, the phone switches Bluetooth to call mode, so Fluent uses the car's microphone and speakers like a phone call (phone-call sound quality). Turn it off in **Settings** if you prefer.
- **Car microphone**: by default Fluent records the call-mode microphone itself (the car's, over Bluetooth) and feeds it to speech recognition (Android 13+), so the car never sees a "voice command" request. **Settings → Microphone** also offers on-phone recognition or the old mode where Google opens the microphone.
- **Diagnostics** in **Settings**: each step of the audio path, the live input level (also shown under the status while listening) and a button to copy it all.
- **Native speech recognition and voices**, without Chrome's restarts.
- A **foreground service** keeps the conversation running with the screen off, with a notification while it lasts; requests to Claude are sent by the app itself so they don't wait for the screen to come back on.

Install:

1. On the phone, open `https://github.com/csileo/fluent/releases/latest/download/fluent.apk` (sign in to GitHub if the repository is private) and open the downloaded file.
2. Allow installing apps from this source when Android asks.
3. Open Fluent, allow the microphone, nearby devices (Bluetooth) and notifications, then paste your Anthropic API key in **Settings**.

Each push to `main` builds a new APK (GitHub Actions) that installs over the previous one. To build it yourself: `./build.sh && gradle -p android assembleDebug` with the Android SDK installed.

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
