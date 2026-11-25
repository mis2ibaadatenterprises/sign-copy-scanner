# Ibaadat Sign Copy Scanner (Android)

Staff photograph a party-signed bill. The app reads the invoice number and party
name on the phone (ML Kit, offline), shows them for a one-tap check, and posts
the result straight into the **Godown Board** web app as a `signcopy` upload.

No second Apps Script, no service account, no separate Drive folder. The sign
copy lands in that godown's day folder and its Bill No goes in the log sheet, so
the board's bill search finds it and shows the ✓ Sign copy button.

## One-time setup

### 1. The signing key — do this first

Android only accepts an update signed with the **same key** as the installed
app. Generate it once and never lose it; losing it means every phone must
uninstall and reinstall.

```bash
keytool -genkeypair -v -keystore release.keystore -alias ibaadat \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Ibaadat Enterprises LLP, O=Ibaadat Enterprises LLP, L=Mumbai, C=IN"
```

It asks for a password twice — use the same one for both prompts. Then:

```bash
base64 -w0 release.keystore > keystore-base64.txt     # Linux
base64 -i release.keystore -o keystore-base64.txt     # Mac
certutil -encode release.keystore keystore-base64.txt # Windows
```

Keep `release.keystore` somewhere safe and out of the repo (`.gitignore`
already excludes it).

### 2. GitHub secrets

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the contents of `keystore-base64.txt` |
| `KEYSTORE_PASSWORD` | the password you typed |
| `KEY_ALIAS` | `ibaadat` |
| `KEY_PASSWORD` | the same password |

### 3. Push

Push to `main`. Actions builds a signed APK and publishes a release. The APK is
always at the same address:

```
https://github.com/<owner>/<repo>/releases/latest/download/invoice-scanner.apk
```

Send that link to staff. For the repo to serve it without a login, the repo must
be public — or host the APK somewhere your phones can reach.

### 4. On each phone

Install the APK, open the app, tap ⚙ and fill in:

- **Web app URL** — the board's `/exec` address
- **Login ID / Password** — that person's board login
- **Update check URL** — `https://github.com/<owner>/<repo>/releases/latest/download/version.json`

Tap **Save & sign in**, then choose the godown for that phone. Every later build
offers itself as an update when the app opens.

## Notes

- Scans taken with no signal queue on disk and upload themselves later; the
  header shows how many are waiting.
- The confirm screen marks each field *read clearly*, *check this* or *not
  found* — staff only need to look closely at the amber and red ones.
- Permission to upload follows the board: a login with `Upload = No` can't post
  sign copies either, and column P (`Sign Copy`) in the Users sheet hides the
  section per person.
