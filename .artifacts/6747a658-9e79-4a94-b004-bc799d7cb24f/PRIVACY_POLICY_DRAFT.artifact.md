# Privacy Policy for VoxApps Suite

**Last Updated: July 2026**

This Privacy Policy applies to the VoxApps ecosystem, including **Vox Commander, Vox Notes, Vox Expenses, Vox Calendar, Vox Vision, and Vox Hub**.

## 🛡️ Core Privacy Principle: 100% On-Device
The fundamental principle of VoxApps is that your personal data belongs to you. Unlike traditional cloud-based assistants and productivity suites, **VoxApps is designed to function entirely offline**.

## 1. Information Collection and Use
VoxApps **does not collect, transmit, or store** any of your personal information on remote servers. All processing is done locally on your hardware.

*   **Voice Data**: Audio captured for wake-word detection and speech-to-text (STT) is processed in real-time on your device. No audio recordings or transcriptions are ever sent to us or any third party.
*   **Personal Records**: Your notes, expenses, calendar events, and scanned documents are stored in an encrypted database (SQLCipher) strictly on your local device storage.
*   **Biometric Data**: We use standard Android biometric prompts for secure access. We do not have access to your actual fingerprint or face data; we only receive the "Success/Failure" signal from the OS.

## 2. Optional Third-Party Integrations
Users may optionally choose to enable cloud-based features. These are **Off by Default** and require explicit user configuration:

*   **Cloud NLU (OpenAI/Gemini)**: If you choose to use a cloud-based AI engine, the transcribed text of your commands (and optionally images for OCR cleanup) will be sent to the respective provider (OpenAI or Google). This data is subject to their own privacy policies.
*   **Media Control (Spotify)**: If you connect your Spotify account, we use the official Spotify SDK to control local playback. No account data is harvested by VoxApps.

## 3. Data Permissions
We request specific permissions only to enable core functionality:
*   **Microphone**: For voice commands.
*   **Camera**: For document scanning.
*   **Notifications**: To capture transaction info (for Vox Expenses) or show active service status.
*   **Files/Media**: To store encrypted backups and attachments.

## 4. Peer-to-Peer Sync (NFC/Bluetooth)
Data synchronization between your own devices is handled directly via NFC and Bluetooth. No cloud middleman or central server is used for this process.

## 5. Contact Us
Since this is an open-source project, any privacy concerns can be raised as an issue on our official repository: [https://github.com/razvan-eduard/VoxApps](https://github.com/razvan-eduard/VoxApps)
