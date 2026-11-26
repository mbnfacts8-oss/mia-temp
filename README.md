# Mia Temp — Floating Email App
### Firebase: https://mia-temp-default-rtdb.firebaseio.com

---

## SETUP STEPS

### Step 1 — Firebase google-services.json fix
1. Firebase Console → Project Settings → "Your apps"
2. Android app add karo (package: com.miatemp.mail)
3. `google-services.json` download karo
4. Replace karo: `app/google-services.json`

### Step 2 — Android Studio me open karo
1. Android Studio install karo: https://developer.android.com/studio
2. "Open" → MiaTemp folder select karo
3. Gradle sync hone do (2-3 min)

### Step 3 — Build APK
1. Menu: Build → Build Bundle(s)/APK(s) → Build APK(s)
2. APK milegi: `app/build/outputs/apk/debug/app-debug.apk`

### Step 4 — Firebase me keys add karo
Firebase Console → Realtime Database → yeh structure banao:

```
users/
  MIA-TEST001/
    password: ""
    expiry: 1900000000000    ← millis (2030 tak)
    hwid: ""
    status: "active"
    maxDevices: 1
```

---

## APP FLOW

1. **Splash** (2 sec) → Login Screen
2. **Login** → Key enter karo → Firebase check → HWID bind
3. **MainActivity** → Overlay permission maango
4. **FloatingService** start → Button screen pe float karta hai
5. **Button tap** → Panel expand → Email generate → Inbox poll

---

## FIREBASE SCHEMA (Exact fields)

```
users/{key}/
  password    → String  (empty = no password check)
  expiry      → Long    (epoch millis, 0 = never expires)
  hwid        → String  (auto-set on first login)
  status      → String  ("active" or "blocked")
  maxDevices  → Int     (1 = one device only)

config/
  maintenance → Boolean
  message     → String

banned/{hwid} → Boolean
```

---

## TEST KEY BANANE KA TARIKA

Firebase Console → Realtime Database → "+" → Add:

```
users
  └── MIA-ABCD1234
        ├── expiry: 1893456000000
        ├── hwid: ""
        ├── maxDevices: 1
        ├── password: ""
        └── status: active
```

App me: `MIA-ABCD1234` enter karo → Login!

---

## TELEGRAM SUPPORT LINK
`LoginActivity.kt` line 47 me apna link daalo:
```kotlin
android.net.Uri.parse("https://t.me/YOUR_BOT_HERE")
```
