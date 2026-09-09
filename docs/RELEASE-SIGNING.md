# Release signing

`v1.0.0-beta.1` is the first APK signed with the dedicated Flight Radar release certificate.

- Subject: `CN=Flight Radar Android, OU=Open Source Project, O=wzk112, C=AU`
- RSA key size: 4096 bits
- SHA-256 certificate fingerprint: `6baa2d7a065bf38559f15999d57e628e804fd14a2378c2f91bf69c5cf182c9a6`
- APK signature scheme: v2

The private keystore is excluded from Git. Local builds inject its path and passwords with `FLIGHTRADAR_KEYSTORE`, `FLIGHTRADAR_STORE_PASSWORD`, and `FLIGHTRADAR_KEY_PASSWORD`. Passwords are held in the macOS Keychain under the account `flight-radar-android`.

All future APK updates for `org.flightdesk.radar` must use this certificate. Losing the private key requires users to uninstall the application before installing a differently signed build.
