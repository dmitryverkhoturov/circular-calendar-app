# Versioning and releases

## Version numbers

[Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- `0.x` until the app is ready for Google Play. After that `1.0.0`.
- **MINOR** — new feature or visible change (`0.16.0 → 0.17.0`).
- **PATCH** — bug fix only, nothing new (`0.17.0 → 0.17.1`).
- **Release candidate** — a build for testing on a real phone: `0.17.0-rc.1`, `-rc.2`, …

A number is never reused and never skipped "just because". The version changes
only together with a git tag.

### In the code (`app/build.gradle.kts`)

- `versionName` = the tag without `v`: `"0.17.0-rc.1"`, `"0.17.0"`.
- `versionCode` = `MAJOR*1000000 + MINOR*10000 + PATCH*100 + N`,
  where `N` is the rc number (1–98) and `99` for the final release.
  `0.17.0-rc.2` → `170002`, `0.17.0` → `170099`.
  So every build installs over the previous one, and the final release is always
  newer than its candidates.

## Branches

- `main` — released code only. Every commit on `main` has a tag.
- `dev` — current work. Experiments and agent loops go to `feature/*` branches off `dev`.

## Release flow

1. **Work on `dev`.** Nothing gets a version number yet.
2. **Release candidate.** When a feature is done:
   - run the checks below — all must pass;
   - set `versionName`/`versionCode` to `X.Y.Z-rc.N`, commit, tag `vX.Y.Z-rc.N`;
   - GitHub Release marked **Pre-release**, with the APK.
3. **Phone test.** The owner installs the rc on a real phone and looks at it.
   Problems → fix on `dev` → next rc (`-rc.2`). No new features in an rc cycle.
4. **Final release** — only after the owner says the rc is accepted:
   - merge `dev` → `main`, set `X.Y.Z` (`versionCode` ends with `99`), tag `vX.Y.Z`;
   - GitHub Release marked **Latest**, notes from `CHANGELOG.md`, APK attached
     as `circular-calendar-vX.Y.Z.apk`.

An agent may build and tag release candidates. It never makes a final release
on its own — that needs the owner's "accepted" after a phone test.

## Checks before every rc

Automatic (emulator `cc`, see `tools/`):

- [ ] `./gradlew assembleRelease` builds without warnings that are new.
- [ ] App installs over the previous version and starts; settings and camera survive.
- [ ] `tools/shots.sh` — all themes × all zoom levels; look through the contact
      sheet: no holes, no missing labels, no clipped events.
- [ ] `tools/seam.sh` at year, month, day and second zoom — no tile seams.
- [ ] Stress calendar (`--ez stress true`) — `CCPERF` scene recording time not
      worse than the previous release.
- [ ] Release notes written in `CHANGELOG.md`.

On the phone (owner):

- [ ] Smooth at the screen refresh rate (frame counter in settings).
- [ ] The new feature works the way it was asked for.
- [ ] Nothing that worked before got worse.

## Hotfix

Bug in a released version → branch from the tag, fix, `X.Y.Z+1-rc.1`,
same checks, phone test, release, merge back to `dev`.
