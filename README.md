# CLIO Scan for Android

Companion app for **CLIO PDF Desktop**. Scan paper documents with the phone camera, make them searchable,
tidy them up and send them straight to your Mac over your own Wi-Fi.

## Features
- **Scan** with automatic edge detection, cropping and clean-up (Google ML Kit document scanner), any number of pages
- **Searchable PDFs**: on-device text recognition adds an invisible text layer (works offline, model bundled)
- **Photos → PDF**, **Import PDF**, **Merge**, **Compress** (3 levels), **Copy text**
- **Organize pages**: reorder, rotate, delete, save or save as copy
- **Send to Mac**: pair once by scanning the QR code in CLIO PDF (File › Pair Android Phone…); files are
  AES-256-GCM encrypted on the phone and go over the local network only. Bonjour finds the Mac when its address changes.
- Share to any app, save to device, "Share to CLIO Scan" from other apps
- Private by design: no account, no analytics, documents kept in app storage and excluded from cloud backup

## Building
Every push to `main` builds a signed APK with GitHub Actions (**Actions › Build APK › Artifacts**).
Pushing a tag such as `v1.0.0` also attaches the APK to a GitHub Release.

Local build: Android Studio (Koala or newer) or `gradle assembleRelease` with JDK 17 and Gradle 8.10.

### Signing key
`signing/clio-scan-release.jks` signs every build so updates install over the previous version.
Keep a backup of it — Android refuses updates signed with a different key. To use your own key instead,
add the repository secrets `CLIO_KEYSTORE_B64`, `CLIO_KEYSTORE_PASSWORD`, `CLIO_KEY_ALIAS`, `CLIO_KEY_PASSWORD`.
Keep this repository private.

## Requirements
Android 8.0 or newer with Google Play services (used by the document and QR scanners; the scanner module is
downloaded once by Play services). CLIO PDF Desktop 2.3 or newer on the Mac for "Send to Mac".

## Protocol (phone → Mac)
`POST http://<mac>:<port>/v1/hello|upload`, headers `X-Clio-Pairing: <pid>`, `X-Clio-Nonce: <base64 12 bytes>`.
Body = AES-256-GCM(key from QR, nonce, AAD = pid) of `[u32 BE header length][JSON header][file bytes]`.
QR: `clio-pdf://pair?v=1&id=<macId>&n=<name>&p=<port>&h=<ip,…>&k=<base64url key>&pid=<pid>`.
Bonjour service `_cliopdf._tcp` with TXT `id=<macId>`.

## Licenses
PDFBox-Android (Apache 2.0), Lucide icons (ISC), AndroidX/Compose (Apache 2.0), ML Kit (Google APIs Terms).
