# Language Learning companion server

This small Rust server supports the Android app's optional AI features:

- finding a related real-world example on a source you choose after you press the search button;
- generating pronunciation audio after you press the speaker button.

Pronunciation requests include the region selected in the Android app, allowing the speech model to use an appropriate regional German or French accent.

Neither feature makes an OpenAI request automatically. The server keeps your OpenAI API key off the tablet, TV, and Android APK.

## Public and legal pages

The server includes responsive pages suitable for the app's Google OAuth configuration:

- Home page: `https://your-domain.example/`
- Privacy Policy: `https://your-domain.example/privacy`
- Terms of Service: `https://your-domain.example/terms`

Replace `your-domain.example` with the HTTPS domain where you host this server. Use that home page in Google's **Application home page** field, `/privacy` in **Privacy policy**, and `/terms` in **Terms of service**. Google's production verification expects the home page and privacy policy on a domain you own, and the home page must link to the privacy policy. The built-in home page already contains both legal links.

Before a broad public launch, replace the contact wording in both legal pages with a dedicated support address and have the policies reviewed for the places where you offer the app.

## One-time setup

1. Install Rust from [rustup.rs](https://rustup.rs/) if `cargo` is not already available.
2. Create an OpenAI API project and a **Restricted** API key.
3. Give the key these permissions:
   - **Responses: Write** for user-requested public-example searches.
   - **Audio/Speech: Write** for pronunciation.
   - Leave unrelated permissions set to **None**.
4. Copy `.env.example` to a new file named `.env` in this folder.
5. Replace `sk-your-key-here` in `.env` with your real API key.

Do not put the API key in `local.properties`, the Android source, or the APK. The `.env` file is excluded by the project's `.gitignore`.

## Start the server

Open Terminal and run:

```bash
cd /Users/arick/Documents/LanguageLearning/server
cargo run --release
```

The first start takes longer because Rust downloads and builds the required packages. When it is ready, Terminal displays:

```text
Language Learning helper listening on http://0.0.0.0:41082
```

Keep that Terminal window open while using the AI features. Stop the server by returning to Terminal and pressing **Control-C**.

## Start with Docker Compose

Docker is convenient when hosting the helper on another computer or server.

1. Install Docker or Docker Desktop.
2. Create and configure `.env` as described above.
3. Open Terminal in this `server` folder and build and start the container:

```bash
docker compose up --build -d
```

The `-d` option runs it in the background. Check whether it is running:

```bash
docker compose ps
```

Follow the server logs:

```bash
docker compose logs -f
```

Press **Control-C** to stop following the logs; this does not stop the background container.

Stop and remove the container:

```bash
docker compose down
```

Start the existing container again later:

```bash
docker compose up -d
```

Rebuild after changing the Rust code:

```bash
docker compose up --build -d
```

The container automatically restarts after a computer reboot unless you explicitly stop it. The `.env` file is supplied at runtime and excluded from the Docker image, so the API key is not baked into the image.

For hosting outside your home network, put the helper behind HTTPS and do not expose its plain HTTP port directly to the public Internet. The current helper is designed for a trusted private network and does not authenticate Android clients.

## Connect the Android app

The computer and Android tablet or TV must be on the same local network.

1. Find the computer's local IP address in macOS under **System Settings → Wi-Fi → Details → TCP/IP**.
2. In the main `LanguageLearning/local.properties` file, add a line like:

```text
SERVER_URL=http://192.168.1.50:41082
```

Replace `192.168.1.50` with the computer's actual local IP address. Do not use `localhost`; on Android, that means the tablet or TV itself.

3. Rebuild and reinstall the Android app after changing `local.properties`.

The Android app uses this one server address for public-example searches, pronunciation audio, and speaking practice.

## Configuration and cost controls

The `.env` file supports:

```text
OPENAI_API_KEY=sk-your-key-here
OPENAI_MODEL=gpt-5.6-luna
OPENAI_TTS_MODEL=gpt-4o-mini-tts
PORT=41082
MAX_UNCACHED_REQUESTS=25
MAX_UNCACHED_SPEECH_REQUESTS=100
```

Why port `41082`? “Four, hex for L, two—get it?” It is a tiny language-learning joke disguised as infrastructure. No one was going to get it without this note.

- `MAX_UNCACHED_REQUESTS` caps public-example search requests until the server restarts.
- `MAX_UNCACHED_SPEECH_REQUESTS` caps newly generated pronunciations until the server restarts.
- Repeated results are cached while the server is running. Pronunciations are also cached on the Android device.
- Set an enforced monthly spend limit on the OpenAI API project for an additional account-level safeguard.

## Troubleshooting

- **The app shows the feature as unavailable:** Confirm the server is still running, both devices are on the same network, and `SERVER_URL` contains the correct computer IP address.
- **Permission error from OpenAI:** Confirm the key has **Responses: Write** and/or **Audio/Speech: Write**, depending on the feature.
- **Connection refused:** Check the address and port, and allow incoming connections if the macOS firewall prompts you.
- **Lookup limit reached:** Restart the server to reset its in-memory counters, or deliberately raise the corresponding limit in `.env`.
- **A cached pronunciation still plays after the server stops:** This is expected; previously generated audio is cached locally on the Android device.
