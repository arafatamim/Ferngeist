# Privacy Policy for Ferngeist

**Last Updated: 2026-04-13**

Ferngeist ("we", "us", or "our") operates the Ferngeist Android application (the "App") and the Ferngeist Desktop Companion (the "Companion"). This page informs you of our policies regarding the collection, use, and disclosure of personal data when you use our software and the choices you have associated with that data.

## 1. Information Collection and Use

Ferngeist is an Android client designed to connect to Agent Client Protocol (ACP) servers. It can connect directly to ACP servers, or optionally through a Desktop Companion for enhanced functionality. We do not operate any central servers and do not collect, store, or process your personal data on our own infrastructure.

### Types of Data Collected

#### Android App — Local Data
The App stores the following information locally on your device to provide its core functionality:
- **Server Configurations:** URLs and metadata for the ACP servers and Desktop Companions you configure.
- **Credentials:** Bearer tokens and authentication keys required to communicate with your configured servers.
- **Session Data:** Chat history, tool calls, and agent interactions.

This data is stored in a local Room database and, for certain sensitive values, using Android's EncryptedSharedPreferences.

#### Desktop Companion — Local Data (Optional)
The Companion is an optional component that runs on your development machine. When used, it stores the following locally:
- **Pairing Credentials:** Tokens and keys issued to paired Android devices, stored in a local SQLite database.
- **Configuration:** Companion name, registry URL, LAN settings, and public base URL.
- **Logs:** Request and error logs stored locally with automatic rotation (configurable size and retention).
- **Managed Binaries:** ACP client binaries downloaded from the registry for local execution.

If you do not use the Desktop Companion, none of this data is collected or stored.

#### Transmission to Third-Party Servers
When you use the App, your data (including prompts and session content) is transmitted only to the servers you have explicitly configured:
- **Direct mode:** The App communicates directly with your ACP servers (e.g., Claude Code).
- **Companion mode:** The App communicates with your Desktop Companion over your local network (or via a tunnel if configured), which then proxies to your ACP servers.

We do not control these third-party servers and are not responsible for their privacy practices. We encourage you to review the privacy policies of any server or service you connect to via Ferngeist.

#### External Service Contact (Desktop Companion Only)
When the Desktop Companion is in use, it contacts the following external service:
- **ACP Registry** (`cdn.agentclientprotocol.com`) — Fetched periodically to discover available ACP client versions and download URLs. No personal data is transmitted; only standard HTTP request metadata (IP address, User-Agent) is visible to the registry operator.

This contact does not occur if the Desktop Companion is not installed.

### Tunnel Services (Desktop Companion Only)
If you configure the Desktop Companion with a public base URL (e.g., via ngrok or Cloudflare Tunnel), your App-to-Companion traffic passes through that tunnel provider's infrastructure. We do not control these services and recommend reviewing their privacy policies before use. By default, the Companion binds to `127.0.0.1` only and does not expose traffic externally.

## 2. Use of Data
The information stored locally on your devices is used solely to:
- Facilitate communication between the App and your configured ACP servers, directly or via the Desktop Companion.
- Maintain pairing relationships, session history, and preferences.
- Provide a consistent user experience across the App and Companion.
- Generate local logs for troubleshooting and diagnostics.

## 3. Disclosure of Data
We do not sell, trade, or otherwise transfer your personal data to outside parties. Your data remains on your devices or is transmitted only to the servers and services you explicitly configure.

## 4. Security of Data

### Android App
We employ standard Android security practices, including the use of the Android Keystore system for encrypting sensitive credentials.

**Note on Local Security:** If your device is rooted or if you enable unencrypted backups, locally stored data may be accessible to other applications or users with physical access to the device.

### Desktop Companion
The Companion binds to `localhost` by default. Pairing credentials are time-limited and rate-limited to prevent abuse. An admin interface is available on a separate port for configuration and diagnostics.

**Note on Local Security:** If you enable LAN access (`FERNGEIST_HELPER_ENABLE_LAN=1`) or expose the Companion via a public URL, other devices on your network or the internet may attempt to connect. Use strong pairing credentials and review tunnel provider security settings.

## 5. Your Data Protection Rights
Since all data is stored locally on your devices, you have full control over it:
- **Access and Portability:** You can view your data within the App or Companion configuration.
- **Deletion:** You can delete all App data by clearing the App's storage in Android settings or uninstalling the App. Companion data (state database, logs) can be deleted from the Companion's data directory on your development machine.

## 6. Children's Privacy
Our software does not address anyone under the age of 13. We do not knowingly collect personally identifiable information from children.

## 7. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date at the top.

## 8. Contact Us
If you have any questions about this Privacy Policy, please contact us via the project's GitHub repository.