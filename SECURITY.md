# Security Policy

CoulombMPPT is proprietary, closed-source software (see [LICENSE](LICENSE)). This
policy covers responsible, private disclosure of security issues.

## Reporting a vulnerability

**Do not open a public issue for a security vulnerability.**

Report it privately through GitHub's **private vulnerability reporting** (Security &rarr; Report a vulnerability). Please
include:

- which app is affected (Android / Windows) and the version;
- a description of the issue and its impact;
- steps to reproduce (and a proof of concept, if you have one).

You can expect an acknowledgement within a few days, and an update on the
assessment and any fix as it progresses.

## Areas that warrant extra care

This project controls real hardware and can be exposed to a network, so the
sensitive surfaces are:

- **The Windows remote API** (`windows/src/CoulombMppt/Api/`) — an in-process
  HTTPS server with HMAC-authenticated clients, a self-signed certificate, and
  optional UPnP port-forwarding. Anything that weakens authentication, exposes
  the certificate/keys, or widens what an unpaired client can reach is in scope.
- **BLE control of the charge controller** — settings writes change real
  charge/cut-off voltages. Treat anything that could send unintended writes as a
  safety issue, not just a software bug.
- **Pairing** (QR / HMAC key exchange) and any stored secrets, keystores, or
  certificates.

## Scope

Issues in third-party dependencies should be reported upstream; Dependabot keeps
those updated here. This policy covers the CoulombMPPT application code itself.
