# Wind-Up

An Android app for friendly AI prank calls. It runs on one phone (the Pixel) and is cabled into a second phone that makes the call. One of six comedy characters does the talking: Claude writes what the character says, Google's Chirp 3 HD voices speak it, and speech recognition on the phone hears your friend's replies. Every prank ends with a reveal that names you.

```
Pixel (Wind-Up app)  ──AI voice──►  call phone's mic
Pixel's mic          ◄──call audio──  call phone's earpiece
                                      call phone ──mobile network──► friend
```

## Install

1. On the Pixel, open the latest release on this repo's Releases page and download `wind-up.apk`.
2. Open it and allow installing from your browser if Android asks.
3. Later builds install over the top; your settings are kept.

## Keys

Both keys are typed into **Settings** and stay on the phone. They never go in this repo.

**Claude (the jokes):** your Claude API key from console.anthropic.com. Each call costs a penny or two.

**Google (the voices):** a Google Cloud Text-to-Speech API key. In **Settings → Voices** you can switch between Google's voices and the phone's own text-to-speech voices. With the phone's voices, each character gets its own pick from the voices installed on the phone (British, Irish, Australian and so on), and **Get more phone voices and accents** opens Android's voice settings to download more. Without a Google key, the app always uses the phone's voices.

1. Go to console.cloud.google.com and create a project, for example "wind-up".
2. Turn on billing for the project. Google needs a card even for the free tier. The first million characters a month are free, which is about a thousand minutes of speech.
3. Search for **Cloud Text-to-Speech API** and click **Enable**.
4. Open **APIs & Services → Credentials → Create credentials → API key**.
5. Click the new key, set **API restrictions** to Cloud Text-to-Speech API only, and save.
6. Optional but sensible: under **Billing → Budgets & alerts**, add a £1 budget so you get an email if anything is ever charged.
7. Paste the key into Wind-Up's Settings.

## The cable

You need two USB-C to 3.5 mm adapters that carry the mic as well as audio (Google's own adapter does), plus two headset splitters to break each 4-pole plug into a headphone lead and a mic lead. Then cross them over:

- Pixel headphone out → attenuator → call phone mic in
- Call phone headphone out → attenuator → Pixel mic in
- Join the grounds.

Each attenuator works like the one on the radio cable. Headphone level is far too hot for a mic input, so knock it down:

```
headphone tip ──┤10µF├──[47kΩ]──┬── mic pin
                                │
                             [2.2kΩ]
                                │
ground ─────────────────────────┴── mic ground
```

The 2.2 kΩ to ground also lets each phone see a "headset with mic" plugged in. Treat these values as a starting point and fine-tune with **Character's volume** in Settings and the call phone's in-call volume.

## Doing a prank

1. Pick a character, type your friend's first name, a few harmless details, and your name for the reveal.
2. Tap **Hear the opening** to check the voice. Swap voices with the **Voice** drop-down if you like.
3. Tap **Get ready**. The opening line is made in advance so it plays instantly.
4. Dial your friend on the call phone. The moment they pick up, tap **They've answered: go!**
5. Wind-Up handles the rest. It does the reveal after about the number of replies you set.

While a call is running:

- **Reveal now** ends the joke on your cue.
- **Stop talking** cuts the character off mid-sentence.
- **Make … say** speaks anything you type in the character's voice.
- **Finished** stops listening and saves the call script on the phone.

**Practise first without the cable:** tap Get ready with nothing plugged in. Wind-Up uses the Pixel's own mic and speaker. Talking over the character is switched off in this mode so it doesn't hear itself.

## Getting the levels right

- Watch **Call line level** while your friend talks. It should go green when they speak and grey between sentences. If it stays grey, lower **Talk threshold**; if it's green during silence, raise it.
- If the character keeps cutting itself off, the call phone is sending its own voice back down the cable. Raise **How loud they must be to interrupt**, or turn off **Let them talk over the character**.
- **Test tone** plays a steady 3-second tone down the cable, handy for checking the call phone hears it.

## Safety net

- The characters never ask for money, bank details, passwords or personal information, and never claim to be the police, a bank or any real organisation or person.
- If your friend says anything on the **Reveal words** list (for example "is this a scam", "call the police", "I'm scared"), Wind-Up stops the joke at once. It says sorry, says it's a prank, and names you.
- Claude is also told to reveal straight away if your friend sounds upset, confused or vulnerable.
- After the reveal, Wind-Up stops listening so you can chat to your friend yourself.

## The characters

All six live in `app/src/main/assets/characters.json`. Edit that file to change a character, or add a new one with the same fields. Each character has an opening line, running gags and an escalation that ends in the reveal, plus:

- `google_voice`: the Chirp 3 HD voice, such as Puck or Gacrux
- `speaking_rate`: 1.0 is normal speed
- `phone_pitch`: the pitch used when there's no Google key
- `voice_design`: a description to paste into ElevenLabs' voice designer if you ever switch to it

| Character | Voice |
|---|---|
| Derek from Speedy Parcels | Puck (upbeat) |
| Mrs Pemberton, Council Bin Inspector | Gacrux (mature) |
| Nigel from Radio Sunshine | Fenrir (excitable) |
| Reginald the Butler | Iapetus (clear), slowed down |
| Inspector Crumb, Garden Gnome Recovery Unit | Algenib (gravelly) |
| Gary from Total Broadband Solutions | Schedar (even) |

## Artwork

The logo and the six character portraits are SVGs in `art/`. The app uses PNG copies of them in `app/src/main/res/drawable-nodpi/` (`portrait_<character id>.png` and `logo_mark.png`). A new character gets its picture automatically if you add a `portrait_<its id>.png` there.

## How it's built

- Kotlin and Jetpack Compose, built by GitHub Actions (`.github/workflows/build.yml`), using the same setup as Control.
- Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with the Moonshine English model and the Silero voice detector, running on the phone.
- Voices: Google Cloud Text-to-Speech, Chirp 3 HD, en-GB (`Voices.kt`), falling back to the phone's own British voice. To add another voice service later, give `Speaker.synth` another branch.
- Jokes: the Claude API (`Brain.kt`). The default model is Claude Haiku, which is quick enough for a phone call.
