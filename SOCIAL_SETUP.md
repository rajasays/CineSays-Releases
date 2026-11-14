# Friends and recommendations (optional Firebase setup)

CineSays v0.6 contains a beta Friends screen, a Recommendations screen, and a multi-recipient Recommend button on title details. These screens use a **real shared backend**, not invented friends. They remain inactive until Firebase is configured.

1. Create a Firebase project and add an Android app with package `com.cineglass.tv`.
2. Enable **Firebase Authentication > Anonymous** and create a **Cloud Firestore** database.
3. In `local.properties` add `FIREBASE_API_KEY=...`, `FIREBASE_APP_ID=1:...:android:...`, and `FIREBASE_PROJECT_ID=...`. These are app configuration values, not administrative credentials.
4. **Before enabling the feature for other people, implement and test strict Firestore security rules** for friend codes, invitations and reciprocal friendships. The initial repository writes across users' documents for invites and recommendations, so naive rules such as `allow read, write: if request.auth != null` are insecure. Do not use an open or test-mode production database. A trusted server/Cloud Function mediating cross-user writes is recommended.
5. Anonymous accounts are tied to an app installation. Add a durable sign-in/linking flow before treating these accounts as permanent.

### Current limitations
- No Android TV installable APK has been built or device-tested here.
- Friends and recommendations require real Firebase provisioning and secure cross-user authorization; UI alone cannot deliver a recommendation to another TV.
- The sample friend-code implementation is a prototype, not a fully production-hardened social system.
- No presence/online indicators or push notifications are claimed.
