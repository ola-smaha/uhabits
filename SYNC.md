# Loop Sync: Google Drive sync for Loop Habit Tracker

This fork adds automatic sync between devices (for example, a phone and a tablet) through the
user's own Google Drive. There is no server: each device keeps its own database and exchanges a
single file stored in the hidden app folder of Google Drive (`drive.appdata` scope).

The app installs next to the original Loop Habit Tracker, as **Loop Sync**
(`org.isoron.uhabits.sync`).

## How it works

- Every change to a habit or a check-in records when it happened. Deleting a habit leaves a
  marker, so the deletion reaches the other devices too.
- A sync downloads the shared file only if another device changed it, merges it into the local
  database and uploads the result if this device has anything new.
- When the same thing is changed on two devices, the most recent change wins. Each habit and
  each day is resolved separately, so check-ins made on different devices never overwrite each
  other.
- Syncs run after every change (with a 5-second delay to group quick taps), when the app is
  opened and every 15 minutes in the background. Changes made offline are uploaded when the
  device is back online.

## One-time setup in Google Cloud

Google only lets an app reach Drive if it is registered in a Google Cloud project. This takes
about 10 minutes and is free.

1. Open the [Google Cloud console](https://console.cloud.google.com/) and create a project
   (for example, "Loop Sync").
2. Enable the [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com)
   for that project.
3. Open **Google Auth Platform** and click **Get started**. Use "Loop Sync" as the app name, your
   email as the support and contact email, and **External** as the audience.
4. In **Data access**, add the scope `https://www.googleapis.com/auth/drive.appdata` (shown as
   "See, create, and delete its own configuration data in your Google Drive"). It is a
   non-sensitive scope, so Google does not need to review the app.
5. In **Audience**, click **Publish app**. Apps left in "Testing" lose access after 7 days.
6. In **Clients**, create a client of type **Android** with:
   - Package name: `org.isoron.uhabits.sync`
   - SHA-1 certificate fingerprint: the SHA-1 of the key that signs the APK. For the releases
     of this fork it is
     `6F:3F:B4:C2:64:21:C0:B4:AF:08:80:76:03:A7:1A:02:0F:B8:29:23`

Nothing else is needed: the app does not embed a client ID, Google recognizes it by package name
and signature.

## Installing

1. Install the signed APK on each device. The same APK works on phones and tablets.
   Once the signing secrets below are set, every push to `dev` publishes a signed APK on the
   [releases page](https://github.com/ola-smaha/uhabits/releases/latest). Without them, CI
   publishes unsigned pre-releases, which must be signed with the release key before installing.
2. To bring over your existing data, open the original Loop app on the device that has it,
   go to **Settings > Export full backup**, then open Loop Sync and use
   **Settings > Import data** with that file. Do this on one device only.
3. On each device, open **Settings > Sync with Google Drive** and choose the same Google
   account.

To keep both devices up to date automatically, you can add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium).

## Building

The GitHub Actions workflow in `.github/workflows/build.yml` runs the tests and builds a signed
APK on every push; pushes to `dev` also publish a release. Signing needs these repository
secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SIGNING_KEY_ALIAS` | the key alias |
| `SIGNING_KEY_PASSWORD` | the key password |
| `SIGNING_STORE_PASSWORD` | the keystore password |

Keep the keystore safe. Android only installs updates signed with the same key, and Google
Drive access is tied to its SHA-1. If you sign with a different key, register its SHA-1 as
another Android client in step 6.

## Troubleshooting

- **"Google rejected this app"**: the package name or SHA-1 registered in Google Cloud does not
  match the installed APK.
- **"Google Drive API has not been used in project…"**: enable the Drive API (step 2).
- **"Sync is paused"**: Google asked to confirm access again. Open Settings and tap
  **Sync with Google Drive**.
- **Turn off sync** stops syncing on that device only. The data stays on the device and in
  Google Drive.
