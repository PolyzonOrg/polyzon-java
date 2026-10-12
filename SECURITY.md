# Security policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.x     | yes       |

Security fixes are released as patch versions of the latest minor release.

## Reporting a vulnerability

Please email **security@polyzon.org** with a description, the affected version and, if
possible, a minimal reproduction. We acknowledge reports within three working days and keep
you informed until a fix is released.

Do **not** open a public GitHub issue, discussion or pull request for a vulnerability, and do
not include real API tokens, personal data or other people's account details in any report.
If a token was exposed, revoke it at https://polyzon.org/account/ first.

## Scope

In scope: this SDK (for example token leaks into logs or exceptions, TLS or redirect handling,
proxy credential handling, parser issues). Issues in the Polyzon service itself
(api.polyzon.org, polyzon.org) are also welcome at the same address.
