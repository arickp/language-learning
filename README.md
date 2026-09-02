# Language Learning

Public legal text for OAuth setup is available in [`docs/PRIVACY.txt`](docs/PRIVACY.txt) and [`docs/TERMS.txt`](docs/TERMS.txt).

An offline Kotlin/Jetpack Compose quiz game for phones, tablets, and Android TV. It includes German content generated from the supplied vocabulary and grammar documents plus a starter French question set.

The home-screen language dropdown supports regional German and French varieties with country flags. All regions share the relevant German or French quiz bank; the selected region controls the accent requested when the user taps the pronunciation button after answering.

The home screen also has a **Mixed / Easy / Medium / Hard** selector. Easy emphasizes core family, number, home, and everyday words; Medium covers general conversation and travel vocabulary; Hard includes less common forms, specialist vocabulary, and deliberately unfamiliar official technology terms. Mixed draws from every skill level at once. Difficulty is saved between launches.

Correct answers play a soft rising chime. Incorrect answers play a quiet two-note “uh-oh.” Both sounds are synthesized locally and follow the device's media volume.

## Screenshots

![Vocabulary quiz on Android: correct answer for “die Geschichte,” with hear/sample-sentence tools, read-aloud feedback, and a Reddit example-search control](docs/screenshot1.jpg)

*A mid-quiz vocabulary screen after a correct answer for **die Geschichte** (“history / story”). The learner can hear the word, get a sample sentence, practice reading it aloud with scored feedback, then optionally search a real-world source such as Reddit.*

![Final vocabulary question with a Reddit usage example for “stören”](docs/screenshot2.jpg)

*End of a 10-question run: **stören** answered correctly as “to disturb / bother,” with a Reddit-sourced real-world example and an **Open on Reddit** button to the cited thread.*

## Open and run

1. Install Android Studio and let its setup wizard install the recommended Android SDK.
2. In Android Studio, choose **Open** and select this `LanguageLearning` folder.
3. Allow Gradle to sync. If prompted, install Android SDK 36.
4. Enable Developer options and USB or wireless debugging on the tablet.
5. Select the tablet in Android Studio and press **Run**.

## Run on Android TV or Google TV

1. On the TV, open **Settings > Device Preferences/System > About**.
2. Select **Build** seven times to enable Developer options. The exact labels vary by manufacturer.
3. Return to Settings, open **Developer options**, and enable **USB debugging** or **Wireless debugging** if the device offers it.
4. Put the TV and computer on the same network. In Android Studio, use **Pair Devices Using Wi-Fi** when the TV supports pairing, or connect through the TV/device manufacturer's supported USB or network-debugging method.
5. Approve the debugging prompt on the TV, select the TV as the deployment target, and press **Run**.

You can also test without a physical TV: in Android Studio's Device Manager, create an Android TV or Google TV virtual device and run the app on it. The interface is operable with a remote directional pad and Select/OK button.

## Customize the questions

The starter German and French vocabulary bank lives in:

`server/seed/quiz_data.json`

After first start, the live word bank is stored in SQLite and is easiest to edit in the server’s `/admin` page. Each vocabulary entry needs a `term` and `translation`. An optional `emoji` is shown beside that word's answer choices; the admin page includes a searchable emoji picker, so editors do not need to find and paste symbols manually. Add `article` and `noun` to automatically create an article question too. The optional `questions` list accepts custom vocabulary, article, or grammar questions with explanations, translations, and progressive hints.

JSON requires double quotes and commas between entries. Android Studio highlights mistakes in the file before you build.

Each SQLite vocabulary entry and custom question has an editable difficulty in the companion server's word-bank manager. Existing entries receive an initial semantic ranking during the database upgrade; manual changes made in the manager are preserved.

Restarting the helper does **not** reload the whole bank from JSON. Seed import runs only when the database is empty. Later starts only insert missing seed rows and update entries marked `core` or `sync` (plus `explicit` / `emoji`). Admin edits and extra rows stay until you reset.

### Reset the word bank to the seed file

Stop the companion server first. Then delete the SQLite file so the next start recreates it from `server/seed/quiz_data.json`:

```bash
rm -f server/data/language-learning.db \
  server/data/language-learning.db-wal \
  server/data/language-learning.db-shm
```

If `DATABASE_URL` in `server/.env` points somewhere else, delete that file instead (and its `-wal` / `-shm` siblings if they exist).

Docker Compose stores the database in the named volume `language-learning-data`. From the `server` folder:

```bash
docker compose down
docker volume rm server_language-learning-data
```

The volume name may differ (`docker volume ls | grep language-learning`). Recreate the container with `docker compose up --build -d`.

After the helper is running again, open **Settings** in the Android app and tap **Clear cache** so it drops the saved word bank and reloads `/api/quiz-data`. You can also force-stop the app.

## Trip quizzes

Choose **Prepare me for my trip** on the Android home screen and paste either itinerary text or a public Google Doc or website URL. The companion server safely downloads public text/HTML links, rejects private-network addresses and responses larger than 1 MB, and sends the itinerary context to OpenAI to generate practical vocabulary and phrases for the selected language and region. Google Docs must be shared publicly so their plain-text export is accessible.

Named venues are used as question material: a restaurant in the itinerary produces menu items, dishes, ingredients, and ordering phrases in that venue's own wording. Memorials, cemeteries, religious sites, and sites of atrocity or disaster are treated with care, so the vocabulary stays practical and respectful rather than graphic. Politically sensitive places such as Tiananmen Square or itineraries in China or North Korea stay on practical visitor language (tickets, photography rules, directions) rather than protests or political history. Adult venues such as gay saunas or sex clubs are used only when explicit content is allowed in Settings; otherwise those places are skipped. Each generated question also comes with its own three wrong answers, so the choices stay relevant to the question instead of being drawn from unrelated words in the shared bank.

The home-screen slider sets 5–100 questions for regular and generated trip quizzes. The choice is saved between launches. A regular category must contain at least that many distinct matching questions; otherwise the app reports how many are available rather than repeating questions.

## Optional real-world examples

After a correct answer, the app lets the learner choose a source and press **Find an example**. German supports Reddit, Bluesky, gutefrage, and Der Spiegel. French supports Reddit, Bluesky, Jeuxvideo.com forums, Radio-Canada, and Le Monde. Nothing is sent to OpenAI until the learner explicitly presses the search button. The learning term, its English meaning, selected language, selected source, and explicit-content preference are then sent through the Rust companion server to OpenAI. The server keeps the API key out of the Android app and caches repeated lookups while it is running.

The app's Settings screen blocks explicit content by default. Changes there are held as a draft until **Save** is pressed, so leaving the screen with **← Back** discards them. Blocking hides quiz vocabulary marked explicit in the word bank (sexual or vulgar terms) and skips NSFW example-search results. Users can opt in; Reddit posts marked `over_18` then display a red **NSFW** badge. The server rejects an `over_18` Reddit result when blocking is enabled. Source links must point to a directly cited post, thread, question, or article rather than a generic search page.

1. In `server`, copy `.env.example` to `.env` and replace the placeholder with your OpenAI API key.
2. Start the helper with `cargo run --release` from the `server` folder.
3. Copy `local.properties.example` to `local.properties` and set `SERVER_URL=http://YOUR_COMPUTER_IP:41082`. Use your computer's local network address, not `localhost`.
4. Keep the tablet or TV on the same network as the computer, then rebuild and install the Android app.

The default model is `gpt-5.6-luna`, the lowest-cost current model verified to support Responses API web search. You can change `OPENAI_MODEL` in `server/.env`. Each uncached button press can incur one model request and one web-search tool call; merely answering correctly does not. `MAX_UNCACHED_REQUESTS` defaults to 25 per server run as a hard safety cap. Setting a monthly project budget in the OpenAI dashboard provides an additional account-level guardrail.