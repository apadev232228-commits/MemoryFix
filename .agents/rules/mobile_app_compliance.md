# Mobile App Compliance & Policy Rules

Whenever building, modifying, or auditing mobile applications (Android / iOS):

## 1. Zero External Payment Links
- **NEVER** link out to external payment, donation, or tip platforms (e.g., Buy Me a Coffee, PayPal, Patreon, Ko-fi, Stripe) from within the app.
- Google Play and Apple App Store policies strictly prohibit external payment links for non-exempt digital goods or donations.
- All digital unlocks, subscriptions, tips, and supporter donations **MUST** use the platform's official In-App Billing APIs (Google Play Billing / Apple StoreKit).

## 2. Permissions & Scoped Storage
- Strictly minimize runtime permissions.
- Always prefer modern Scoped Storage and Storage Access Framework (SAF / Photo Picker) over broad storage permissions (e.g., avoid `MANAGE_EXTERNAL_STORAGE` or raw external read/write).
- Declare appropriate Foreground Service Types (`dataSync`, `mediaPlayback`, etc.) required on modern Android (API 34+).

## 3. Privacy & Data Deletion
- Ensure every app includes an easily discoverable in-app Privacy Policy link and Contact/Data Deletion instructions.
- Ensure all third-party SDK data practices (AdMob, Billing, Firebase) align with the declared Google Play Data Safety section.

## 4. Metadata & Trademark Safety
- Do not use third-party trademarked names (e.g., "Snapchat", "WhatsApp") in the app name, icon, or short description to prevent automated store rejections.
- Use generic terms in store listings (e.g., "social media archive", "camera export").

## 5. Lifecycle & Stability
- Properly configure `android:configChanges` and lifecycle handlers so device rotations, multi-window splits, and foldables do not disrupt long-running tasks, live streams, or background processing.
