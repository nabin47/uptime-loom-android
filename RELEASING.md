# Releasing Uptime Loom

Day to day, releasing is one command:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The **Release** workflow then:

1. builds the release APK and App Bundle (AAB), version `1.0.0` / code `10000`
2. publishes a GitHub Release with both files and auto-generated notes
   (tags with a dash, like `v1.1.0-beta.1`, become pre-releases)
3. uploads the AAB to Google Play's **internal testing** track (if configured)
4. rebuilds the website so the download button and release list update

The one-time setup is below.

## 1. Website (GitHub Pages)

Repository **Settings → Pages → Build and deployment → Source: GitHub Actions**.
Then run **Actions → Website → Run workflow** once. The site appears at
`https://nabin47.github.io/uptime-loom-android/`.

When the app is live on Google Play, add a repository variable so the website
shows the Play button: **Settings → Secrets and variables → Actions →
Variables → New variable** `PLAY_STORE_URL` =
`https://play.google.com/store/apps/details?id=io.github.nabin47.uptimeloom`.

## 2. Upload signing key

Google Play needs releases signed with *your* upload key. Create it once on
your own computer (keep the file and passwords safe — you need them for every
future update):

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository **secrets** (Settings → Secrets and variables →
Actions → Secrets):

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | output of `base64 -w0 upload.jks` (macOS: `base64 -i upload.jks`) |
| `UPLOAD_KEYSTORE_PASSWORD` | the keystore password |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_KEY_PASSWORD` | the key password |

Without these, releases are signed with a debug key: fine for sideloading the
APK, but Google Play will reject the AAB.

## 3. Google Play Console (first time)

1. Create a developer account (one-time US$25) at play.google.com/console.
2. **Create app** → name "Uptime Loom", free, app.
3. Upload the first AAB **by hand** (Testing → Internal testing → Create
   release). Play requires the very first upload to be manual; choose
   **Play App Signing** (recommended).
4. Fill in **App content**:
   - Privacy policy: `https://nabin47.github.io/uptime-loom-android/privacy.html`
   - Data safety: the app collects **no data** (nothing leaves the device).
     Location is processed on the device only.
   - **Location permissions / background location declaration**: core
     feature = "automatically records work time when the user arrives at and
     leaves work areas they set". Google asks for a short video showing the
     in-app explanation (Today → Finish setting up → Allow location) and the
     feature working.
   - Ads: no. Target audience: 18+ (adults).
5. New personal developer accounts must run a **closed test with at least 12
   testers for 14 days** before production access. Invite colleagues to the
   closed testing track.
6. Then promote the release to **Production**.

## 4. Automatic upload to Play (optional)

1. Play Console → Setup → API access → link a Google Cloud project and create
   a **service account**; grant it "Release to testing tracks" for this app.
2. Create a JSON key for the service account and save its contents as the
   repository secret `PLAY_SERVICE_ACCOUNT_JSON`.

From then on every tag also uploads the AAB to the internal testing track;
promote it to production in the Play Console when you're happy.
