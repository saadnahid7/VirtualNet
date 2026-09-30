# Contributing

Thanks for helping. A few notes keep changes easy to review.

## Reporting a problem

Open an issue and include:

- Android version and ROM
- Framework and version (Vector, LSPosed, and so on)
- Which coverage setting you use (System, Apps, Both) and which scope entries you added
- The output of the Lab app for the mode that misbehaves (use **Copy report**)
- For crashes, a filtered logcat: `adb logcat -s VirtualNet VNL`

## Changing code

- Hooks must never throw into the target. Run the real method first, rewrite the result inside a `try`, and fall back to the real result on any failure. Log a failure once.
- The system layer runs inside `system_server`. Keep it small, never edit the service's own objects (copy first), and gate every change by the caller uid.
- Hidden APIs differ across Android 9 to 17. Use the reflection helpers in `hooks/common/Refl.kt`, expect misses, and verify on the versions you touch.
- Keep the app small: no AppCompat, no Material, no network permission.

## Testing

`scripts/matrix.py <api> ...` boots rooted emulators and checks every mode with the Lab app. `scripts/deploy.sh` and `scripts/lab-run.sh` do the same on one device. Add the result to `docs/TESTING.md` for the device you used.

## Pull requests

Keep them focused, explain what you verified and on which Android versions, and update `CHANGELOG.md` for user-visible changes.
