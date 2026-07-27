# Google Play Permission Justification Drafts

When submitting VoxApps to Google Play, you will be required to fill out forms for sensitive permissions. Use these drafts to ensure a high chance of approval.

---

## 1. QUERY_ALL_PACKAGES (Vox Commander)

**User-facing feature description:**
The "App Management" and "Intent Routing" features allow users to launch any installed application by name using voice commands (e.g., "Open Spotify" or "Play music on YouTube").

**Technical Justification for Google:**
Vox Commander is an on-device voice assistant. Its core functionality depends on its ability to discover, index, and launch other applications installed on the user's device based on natural language intents. Without this permission, the app cannot resolve user requests to interact with third-party apps, which is a primary use case for an assistant-class application.

*   **Policy Compliance:** We use the data solely for local intent routing. No application list is ever transmitted off the device.

---

## 2. QUERY_ALL_PACKAGES (Vox Expenses)

**User-facing feature description:**
"Notification Capture" allows users to select specific banking or payment apps to monitor for incoming transaction notifications, which are then automatically converted into encrypted expense records.

**Technical Justification for Google:**
Vox Expenses provides an automated spending tracker. To function, it needs to allow the user to select which specific apps are "financial sources" from the full list of installed applications. This enables the `NotificationListenerService` to filter and process only relevant payment alerts while ignoring unrelated notifications, ensuring user privacy and data accuracy.

---

## 3. SYSTEM_ALERT_WINDOW (Vox Commander)

**User-facing feature description:**
"Floating Voice Overlay" provides real-time visual feedback and transcription while the user is speaking, regardless of which app is currently in the foreground.

**Technical Justification for Google:**
As a voice assistant, Vox Commander must provide immediate visual feedback to the user during speech interaction. The overlay UI allows the user to see the real-time transcription and system status without being forced to leave their current application. This is essential for a hands-free, seamless multi-tasking experience.

---

## 4. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Vox Commander & Expenses)

**Technical Justification for Google:**
*   **Commander:** Required for the "Always-on Wake Word" feature. To ensure the assistant responds to the wake word reliably, the background service must not be throttled or killed by the OS power management.
*   **Expenses:** Required for the "Durable Notification Queue." Ensuring the app captures time-sensitive bank notifications is critical for financial data integrity. Throttling would lead to missed transactions.
