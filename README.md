# AA Studio (Android project)

AA Studio is a lightweight Android WebView app focused only on:
- **Menu Editor**: manage web sources in a 16:9 scene canvas; select, move, resize, hide/show, and delete sources.
- **Web Source**: enter a website URL, preview it, and add/update it as a scene source.
- Save/restore scene locally and import/export scene JSON.

## Build
Open this folder in Android Studio and build the `app` debug variant. An Android SDK and a compatible Gradle installation are required.

## Notes
- This is a scene/web-source editor prototype, not a live-stream encoder and not a streaming service integration.
- Some websites block iframe embedding or require authentication; those sites may not render in the preview.
- Website content is loaded from the URL. Scene metadata is stored locally in WebView storage.
- App logo is the black `AA` text on white background.
