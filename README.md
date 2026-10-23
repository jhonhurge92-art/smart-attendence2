# Smart Attendance (Android)

Register students with a name, roll number and reference photo. Then open the camera: students are
recognised on the device and marked present automatically, with no button press. A blue-and-white
dashboard shows Present, Absent and Attendance %.

* Language / UI: Kotlin, XML views, Material Components (blue + white theme)
* Camera: CameraX
* Face detection: Google ML Kit (bundled model, works offline)
* Face recognition: on-device, see "How recognition works"
* Storage: local SQLite, nothing leaves the phone
* Min Android: 8.0 (API 26)

> **Status:** the source was written without access to the Android SDK or Maven repositories, so it
> has **not been compiled or run yet**. Expect that your first build might surface a small compile
> error to fix. Everything else below is what you do to get from source to an installed APK.

---

## 1. Get the APK (pick one)

### Option A: Android Studio (most reliable)
1. Install Android Studio (current stable): https://developer.android.com/studio
2. Unzip this project, then **File > Open** and choose the `SmartAttendance` folder.
3. Wait for "Gradle sync" to finish (first sync downloads dependencies; needs internet).
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Click "locate" in the notification. The file is
   `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: command line
Needs JDK 17 and the Android SDK (platform 34). Set `ANDROID_HOME` to the SDK folder, then:
```
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

### Option C: GitHub Actions (no Android Studio needed)
1. Create a new GitHub repository and upload the contents of this folder (keep `.github/`).
2. Open the **Actions** tab > **Build APK** > **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the artifact **SmartAttendance-debug-apk**
   (a zip containing `app-debug.apk`).

If a build fails, copy the first red error line and send it to me; usually it is a one-line fix.

---

## 2. Install on your phone

**Easiest (file copy):**
1. Send `app-debug.apk` to the phone (USB, Drive, WhatsApp "document", email).
2. Open it. When Android asks, allow "Install unknown apps" for the app you opened it from.
3. Tap **Install**, then **Open**.

**With USB debugging (adb):**
1. On the phone: Settings > About phone > tap *Build number* 7 times, then Settings >
   Developer options > enable **USB debugging**.
2. Connect the phone, accept the prompt, and run: `adb install -r app-debug.apk`

---

## 3. Test it step by step

1. **Open the app.** Dashboard shows 0 present / 0 absent / 0%.
2. **Register student:** tap *Register student*, take a photo (or pick one), type name and roll
   number, and wait for "Face detected ✓". Then tap *Save student*.
   Use a sharp, well-lit, front-facing photo with only one face.
3. Register a second person (a friend, or a photo of someone shown on another screen).
4. Tap *Start attendance* and allow the camera. Look at the camera. Status text shows
   "Recognising NAME… 1/3, 2/3", then "✓ NAME marked present". The name also appears under
   *Marked present*.
5. Tap *Done*. The dashboard now shows Present / Absent / % updated and the student's chip reads
   "Present · HH:MM".
6. Re-scan the same person: it says "already marked" (one record per student per day).
7. **Tune the threshold** with the slider on the camera screen (see below).
8. Long-press a student on the dashboard to remove them.

---

## How recognition works (and its limits)

Per frame: ML Kit finds faces > the face is cropped and rotated upright > a feature vector is
computed > it is compared (cosine similarity) with each student's stored vector. A student is
marked only after **3 consecutive matching frames**, and when 2+ students are registered the best
match must beat the runner-up by a margin. Each student is stored with both the normal and
mirrored reference photo, because some front cameras save selfies mirrored.

**Built-in engine (default):** LBP-histogram features. It needs no extra files, but it is a classic
technique and is sensitive to lighting, angle and glasses. The numeric scores shown on screen
("Unsure (closest: Ali, score 0.912)") exist so you can tune it: set the slider just below the
score a registered person gets when correctly in front of the camera and above what other
people get. **The default threshold is a starting guess; it has not been calibrated on real
faces.** Treat results from this engine as a working prototype.

**Better accuracy (recommended before real classroom use):** drop a float32 face-embedding TFLite
model (for example a MobileFaceNet or FaceNet model, 112x112 or 160x160 input) into
`app/src/main/assets/` named exactly `face_model.tflite`, then rebuild. The app detects it
automatically, reads the input size and output length from the model, rebuilds existing students'
vectors from their saved photos, and shows "neural model" under the slider. Its default threshold
(0.65) will likely also need tuning with the slider. I could not download a model from the build
environment, so none is bundled; choose one whose licence suits your use.

Other limits: no liveness check (a printed photo or phone screen of a student can be recognised),
single reference photo per student, and attendance is one record per student per day.

---

## Project layout
```
app/src/main/java/com/smartattendance/app/
  MainActivity.kt        dashboard (present / absent / %)
  RegisterActivity.kt    name + roll no + photo, validates exactly one face
  AttendanceActivity.kt  live camera, automatic marking
  StudentAdapter.kt      dashboard list
  data/                  Db.kt (SQLite), Models.kt, ImageUtil.kt
  face/                  FaceEngine.kt (detect/align/match), Embedders.kt (LBP + TFLite)
```
Privacy: photos and face vectors are stored only in the app's private storage on the phone.
