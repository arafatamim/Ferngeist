# Privacy Policy for Ferngeist

**Last Updated: 2026-04-11**

Ferngeist ("we", "us", or "our") operates the Ferngeist Android application (the "App"). This page informs you of our policies regarding the collection, use, and disclosure of personal data when you use our App and the choices you have associated with that data.

## 1. Information Collection and Use

Ferngeist is an Android client designed to connect to Agent Client Protocol (ACP) servers and optionally to a self-hosted [Ferngeist ACP Gateway](https://github.com/arafatamim/ferngeist-acp-gateway). We do not operate any central servers and do not collect, store, or process your personal data on our own infrastructure.

### Types of Data Collected

#### Local Data
The App stores the following information locally on your device to provide its core functionality:
- **Server Configurations:** URLs and metadata for the ACP servers and Ferngeist Gateways you configure.
- **Credentials:** Bearer tokens and authentication keys required to communicate with your configured servers.
- **Session Data:** Chat history, tool calls, and agent interactions.

This data is stored in a local Room database and, for certain sensitive values, using Android's EncryptedSharedPreferences.

#### Transmission to Third-Party Servers
When you use the App, your data (including prompts and session content) is transmitted directly to the **ACP servers** and **Ferngeist Gateways** that you have explicitly configured. We do not control these third-party servers and are not responsible for their privacy practices. We encourage you to review the privacy policies of any server or service you connect to via Ferngeist.

## 2. Use of Data
The information stored locally on your device is used solely to:
- Facilitate communication between the App and your configured ACP servers.
- Maintain your session history and preferences.
- Provide a consistent user experience across the App.

## 3. Disclosure of Data
We do not sell, trade, or otherwise transfer your personal data to outside parties. Your data remains on your device or is transmitted only to the servers you specify.

## 4. Security of Data
The security of your data is important to us. We employ standard Android security practices, including the use of the Android Keystore system for encrypting sensitive environment variables. 

**Note on Local Security:** If your device is rooted or if you enable unencrypted backups, locally stored data may be accessible to other applications or users with physical access to the device. We recommend using a secure device and being cautious when configuring connections to untrusted servers.

## 5. Your Data Protection Rights
Since all data is stored locally on your device, you have full control over it:
- **Access and Portability:** You can view your data within the App.
- **Deletion:** You can delete all data associated with the App by clearing the App's storage in your Android system settings or by uninstalling the App.

## 6. Children's Privacy
Our App does not address anyone under the age of 13. We do not knowingly collect personally identifiable information from children.

## 7. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last Updated" date at the top.

## 8. Contact Us
If you have any questions about this Privacy Policy, please contact us via the project's repository.
