<!--
Keep this PR a focused patch — see CONTRIBUTING.md.
Fill in the section below and tick the boxes that apply.
-->

## What does this change?

<!-- A sentence or two: what changed and why. Link any related issue, e.g. "Fixes #12". -->

## Apps touched

- [ ] Android (`android/`)
- [ ] Windows (`windows/`)
- [ ] Shared protocol / `docs/BLE_PROTOCOL.md`

## Checklist

- [ ] This PR does **one thing** — unrelated changes are split out.
- [ ] The diff is a **focused patch**: only the lines that needed to change, no wholesale rewrites or drive-by reformatting.
- [ ] Builds locally and **CI is green** (Android `assembleDebug` + Windows `dotnet build`).
- [ ] If the BLE/Modbus protocol changed, **both apps** and `docs/BLE_PROTOCOL.md` were updated together.
- [ ] No secrets, keystores, certificates, or confidential hardware details are committed.
