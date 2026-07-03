# Paper Monitor PDF Capture for Firefox

This extension captures authenticated PDF responses from a provider tab and
uploads a copy to the paper selected in Paper Monitor. The original response is
passed through unchanged, so Firefox still opens or downloads the PDF normally.

## Development installation

1. Open `about:debugging#/runtime/this-firefox`.
2. Select **Load Temporary Add-on**.
3. Select `manifest.json` from this directory.
4. Open Paper Monitor and use **Paper actions → Capture provider PDF**.
5. In the provider tab opened by the extension, navigate as needed and start
   the PDF download.

Temporary installations are removed when Firefox exits. Production deployment
requires packaging this directory as an XPI and signing it through Mozilla.

## Permissions

The extension requests access to all sites because provider and institutional
authentication domains cannot be known in advance. It observes response bodies
only in a provider tab explicitly armed from Paper Monitor, stops after the
first matching PDF, and stores capture tokens only until completion or expiry.

The Paper Monitor content script is restricted to:

- `https://papers.home.nextnet.top/*`
- `http://localhost:8080/*`

Add another Paper Monitor deployment origin to `content_scripts.matches` before
packaging if needed.

## Limits

- Maximum captured PDF size: 50 MiB.
- Capture lifetime: 10 minutes.
- `blob:` downloads, DRM viewers, and proprietary document streams are not
  observable as ordinary PDF responses. Use Paper Monitor's manual PDF upload
  for those cases.
