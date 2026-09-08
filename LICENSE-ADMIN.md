# VProxies SA license administration

VProxies SA 1.5.0 uses offline ECDSA P-256 signed licenses bound to one Windows computer.

## Issue a license

1. Ask the customer to copy the Device ID from the activation window.
2. On a private Windows computer, build the generator once:
   `./build-private-keygen.ps1 -PrivateKeyPath "C:\secure\VProxiesSA-private-key.pem"`
3. Keep the generated ZIP and PEM private. Open `VProxiesSA-Keygen.exe`.
4. Enter the customer or order note, paste the Device ID, and choose Lifetime or an expiration date.
5. Generate the license and send only the resulting `VPSA1...` key to the customer.

## Security rules

- Never commit the private PEM or the built key generator to a repository.
- Never include either private item in the customer installer.
- Back up the PEM securely. Losing it means no new keys can be issued for this public key.
- The public key in the application verifies licenses but cannot create them.
- Offline licensing raises the effort required to copy a license; it cannot make a client-side application impossible to patch.
