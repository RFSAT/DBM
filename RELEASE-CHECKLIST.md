# Release checklist (per version bump)

Every release bump touches these in lockstep. The first three were always done;
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
5. **Package** — `zip -rq DBM-vX.Y.Z-code-only.zip DBM -x "*.tflite"` into
   `/mnt/user-data/outputs/`, plus any changed tools scripts.

The test for step 4: "if someone read only OPEN-ITEMS.md / PLAY-COMPLIANCE.md,
would they have a true picture of the project after this release?" If not, they
need an edit.
