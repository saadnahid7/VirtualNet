# Publishing

How VirtualNet is released and submitted. Package id: `com.droidrooter.virtualnet`.

## 1. Release build

```bash
./gradlew :app:assembleRelease
```

With `.local/signing.properties` present the APK is signed with the release key. Without it the build is unsigned, which is what F-Droid uses (it signs with its own key).

The release key lives in `.local/virtualnet-release.jks` and is never committed. **Back it up.** Losing it means users cannot update a signed release in place.

Rename the APK `VirtualNet-<versionName>.apk` and attach it to a GitHub release tagged `v<versionName>` in this repo.

## 2. LSPosed and Vector repository (modules.lsposed.org)

Vector's manager reads the same repository, so this one submission covers LSPosed and Vector.

The old `repo.xposed.info` is a legacy repository for the original Xposed API. VirtualNet uses the modern libxposed API, which legacy Xposed cannot load, so it is not submitted there.

1. **Prove the package id is yours.** Add a DNS TXT record on the root of `droidrooter.com`:

   | Type | Host | Value |
   |---|---|---|
   | TXT | `@` | `lsposed-modules-repo-verification=saadnahid7` |

   DNS for `droidrooter.com` is at Namecheap. Keep the existing Zoho and SPF records.
2. Open <https://modules.lsposed.org/submission>, choose **Submit a new package**, enter `com.droidrooter.virtualnet` and a short description, then **Submit on GitHub**. This creates the issue `[submission] com.droidrooter.virtualnet` in `Xposed-Modules-Repo/submission`.
3. A bot creates the repository `Xposed-Modules-Repo/com.droidrooter.virtualnet` and invites the submitter.
4. In that repository:
   - Set the repository description to the module name: `VirtualNet`.
   - Put the README there (copy of the project README).
   - Create a release with tag `<versionCode>-<versionName>`, for example `2-1.0.1` (`versionCode-versionName`) and attach the signed APK.

## 3. F-Droid

F-Droid builds from source. VirtualNet builds reproducibly, so F-Droid publishes the APK signed with **our** key: it rebuilds the tagged commit, checks it matches the release APK, and copies our signature.

What makes that work:

- Release builds are made from a clean checkout of the tag (line endings are pinned to LF in `.gitattributes`).
- The Google dependency metadata block and VCS info are removed from the APK (`app/build.gradle.kts`).
- `AllowedAPKSigningKeys` holds the SHA-256 of the release certificate, and `Binaries` points at the GitHub release APK.

Steps:

1. Tag the release commit `v<versionName>` and attach the signed APK named `VirtualNet-<versionName>.apk` to the GitHub release.
2. Sign in to GitLab, fork <https://gitlab.com/fdroid/fdroiddata>, and create a branch named `com.droidrooter.virtualnet`.
3. Add `metadata/com.droidrooter.virtualnet.yml` (LF line endings). The current file is [`fdroid-metadata.yml`](fdroid-metadata.yml). The `commit` field must be the full hash of the tag.
4. Open a merge request titled `New app: VirtualNet` and work through the lint and build results. The build needs JDK 17+ and Android platform 37.

## 4. Each new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and `lab/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and a `CHANGELOG.md` entry.
3. Tag `v<versionName>`, build, and attach the signed APK to the GitHub release.
4. Add the same APK to the LSPosed repository release with tag `<versionCode>-<versionName>`.
5. F-Droid picks up new tags automatically (`UpdateCheckMode: Tags`).
