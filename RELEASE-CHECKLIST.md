# Release checklist (per version bump)

## ONE version number, for everything

There is a single version scheme — `dmsVersionMinor` in
`android/app/build.gradle.kts`, from which `versionName` and `versionCode`
derive — and it covers every kind of change: app code, CI, tooling, docs,
housekeeping. There is no separate "housekeeping" numbering, no GitHub-only
numbering and no Play-only numbering.

The consequences, which are the whole point:

- **Every commit bumps the version.** A docs-only or CI-only change is a version
  like any other. Nothing lands on main carrying a version that is already used.
- **What GitHub builds is what Play gets.** Each pushed commit yields one AAB
  whose version is the highest so far, so the newest commit is always the one to
  submit — no reasoning about which build corresponds to which number.
- **Never move an entry between versions after it is pushed.** If a change is
  already on main under version N, it belongs to N for good; describe the
  correction under the new version instead of rewriting history.

Every bump touches the items below in lockstep. The first three were always done;
the docs (4) were historically forgotten — they are now part of the routine.

1. **Version** — `android/app/build.gradle.kts`: bump `dmsVersionMinor`
   (versionName/versionCode derive from it).
2. **CHANGELOG.md** — prepend an entry describing what changed and why.
3. **README.md** — update the "Current version: vX.Y.Z" line.
4. **Living docs — update when the release changes their subject matter:**
   - **OPEN-ITEMS.md** — did this release close an open item, add a new
     unverified-on-device feature, or create new "still to build" work? Move/add
     items accordingly. Always refresh the "current as of" version line.
   - **PLAY-COMPLIANCE.md** — did this release touch minification/R8, target SDK,
     AGP/Gradle, native libraries, the CI alignment check, or anything Play flags?
     Update the relevant section and the "current as of" line.
   If a release genuinely doesn't affect these, at minimum bump their
   "current as of vX.Y.Z" line so it's clear they were reviewed, not forgotten.
5. **Land it** — write the changed files straight into the working copy at
   `C:\Users\artur\Documents\GitHub\DBM\` (CRLF, matching `* text=auto`),
   then commit and push from there; CI builds the release APK + AAB for that
   version. A `DBM-vX.Y.Z-code-only.zip` (excluding `*.tflite`) is optional and
   archival only — the repo is the source of truth, not the zip.

The test for step 4: "if someone read only OPEN-ITEMS.md / PLAY-COMPLIANCE.md,
would they have a true picture of the project after this release?" If not, they
need an edit.
