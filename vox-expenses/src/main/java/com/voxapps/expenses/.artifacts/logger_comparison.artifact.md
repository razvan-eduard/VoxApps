# Logger Implementation Comparison

We have two distinct `Logger` implementations in the project. One is a lightweight wrapper for standard apps, and the other is a heavy-duty diagnostic tool for the background service (Commander).

## 1. Core Logger (`:core:logging`)
**Location**: `com.voxapps.logging.Logger`
**Used by**: `vox-expenses`, `vox-vision`, `vox-notes`

| Feature | Status | Description |
| :--- | :--- | :--- |
| **Output** | Logcat + Toasts | Prints to standard Android logs; recently added optional Toasts. |
| **Persistence** | None | Logs are gone once they leave the screen or logcat buffer. |
| **Threading** | Basic | Main-thread dependent for Toasts; standard Logcat for others. |
| **Complexity** | Lightweight | 50 lines of code. No impact on app performance. |

---

## 2. Commander Logger (`vox-commander`)
**Location**: `com.voxapps.commander.utils.Logger`
**Used by**: `vox-commander` (The Service Hub)

| Feature | Status | Description |
| :--- | :--- | :--- |
| **Output** | Multi-Channel | Logcat, Toasts, AND an In-Memory Buffer. |
| **Persistence** | Last 100 Logs | Keeps a rolling list of the last 100 events in RAM. |
| **State Support** | `StateFlow` | Exposes logs as a stream that Compose UI can "watch" in real-time. |
| **UI Component** | In-App Viewer | Commander has a hidden screen to view these logs directly on the phone. |
| **Complexity** | Heavyweight | 150+ lines of code. Includes `MutableStateFlow` and Coroutine support. |

---

## Technical Gap Analysis
The **Core Logger** is "incomplete" because it cannot show you a history of what happened while the phone was in your pocket or if a scan failed 5 minutes ago.

**Recommendation**: We should unify these. Port the `StateFlow` buffer from Commander into Core so that **Expenses** can also have an "In-App Log Viewer" in the Settings.