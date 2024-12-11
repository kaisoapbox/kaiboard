# Kaiboard

<a href="https://play.google.com/store/apps/details?id=kaizo.co.WhisperVoiceKeyboard">
  <img src="https://cdn.rawgit.com/steverichey/google-play-badge-svg/master/img/en_get.svg" width="25%">
</a>

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/kaisoapbox)

## _The best Android keyboard for offline speech recognition_

The power of OpenAI's Whisper model at your fingertips, anywhere, anytime. A native Android keyboard using [whisper.cpp](https://github.com/ggerganov/whisper.cpp/) for speech transcription. No servers, full privacy.

This project is developed and maintained with ❤️ by [Kai](https://kaisoapbox.com/).

## How to Run

Add the model you want to use under `app/src/main/assets/models/` (e.g. `ggml-tiny.en.bin`). Then, just open the project in Android Studio, and it should work!

## Build

Select the "release" active build variant, and use Android Studio to run and deploy to your device.

You can use Android Studio to generate a signed app bundle for Google Play deployment (you will need/create your own signing key) under `Build -> Generate Signed App Bundle or APK`.

## Roadmap

- [ ] Model and language selection
- [ ] Local logging to assist debugging
- [ ] Transcribe any provided file
- [ ] Realtime text preview of transcription
- [ ] Setting to save audio recordings as files

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Acknowledgements

Many thanks to [Michael McCulloch](https://github.com/MichaelMcCulloch/WhisperVoiceKeyboard) for his first version of the keyboard, and to all the folks working on [whisper.cpp](https://github.com/ggerganov/whisper.cpp/), without whom this app would not exist.
