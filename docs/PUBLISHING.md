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
   - Create a release with tag `1-1.0.0` (`versionCode-versionName`) and attach the signed APK.

## 3. F-Droid

F-Droid builds from source and signs with its own key.

1. Tag the release commit `v1.0.0` in this repo (fastlane metadata and the changelog for version code 1 are already in the repo).
2. Sign in to GitLab, fork <https://gitlab.com/fdroid/fdroiddata>, and create a branch named `com.droidrooter.virtualnet`.
3. Add `metadata/com.droidrooter.virtualnet.yml`:

```yaml
Categories:
  - System
License: MIT
AuthorName: DroidRooter
WebSite: https://droidrooter.com
SourceCode: https://github.com/saadnahid7/VirtualNet
IssueTracker: https://github.com/saadnahid7/VirtualNet/issues
Changelog: https://github.com/saadnahid7/VirtualNet/blob/HEAD/CHANGELOG.md

AutoName: VirtualNet

RepoType: git
Repo: https://github.com/saadnahid7/VirtualNet.git

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```

4. Open a merge request against `fdroid/fdroiddata` and work through the lint and build-server results. The build needs JDK 17+ and Android platform 37.

## 4. Each new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and `lab/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and a `CHANGELOG.md` entry.
3. Tag `v<versionName>`, build, and attach the signed APK to the GitHub release.
4. Add the same APK to the LSPosed repository release with tag `<versionCode>-<versionName>`.
5. F-Droid picks up new tags automatically (`UpdateCheckMode: Tags`).
