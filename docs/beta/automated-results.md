# Automated beta baseline

Recorded on 2026-11-10 at 08:46 UTC against the current working tree. This records automated checks, not observations from human participants.

| Check | Result |
| --- | --- |
| Maven regression suite | 284 passed; 0 failures; 0 errors; 1 skipped out of 285 tests |
| Chromium passport journey | 17 steps passed |
| Browser errors recorded | 0 |
| Human usability sessions | Not yet run |

The skipped test is the optional Mailpit delivery integration. Real SMTP delivery was not verified by this run.

## Browser coverage

The journey used separate owner, buyer, luthier, administrator and anonymous browser sessions, normal password login and rendered forms. Its app ran on a temporary loopback port with an isolated in-memory database, then shut down.

- Passed: Start an isolated application and seed the standard demo accounts.
- Passed: Log in as two members and a luthier using separate browser sessions.
- Passed: Read public Markdown guides, follow guide links, download source and switch language.
- Passed: Publish the luthier workshop through its form.
- Passed: Musician and luthier privately arrange and confirm an appointment with a counter-proposal.
- Passed: Owner creates an instrument with private notes, history and a real PNG upload.
- Passed: Upload a private invoice and certificate; both are private until explicitly selected.
- Passed: Manage personal labels, filter archived uploads, restore and delete an unused file.
- Passed: Owner requests an authenticity assessment; luthier receives only the selected request.
- Passed: Luthier records an assessment and then a correction without replacing the first revision.
- Passed: Luthier keeps an invoice private and explicitly shares evidence with future report readers.
- Passed: Owner proposes a transfer; buyer opens the notification and sees only basic instrument details.
- Passed: Buyer accepts; the same instrument and both report revisions follow the passport.
- Passed: Buyer downloads only the selected certificate; unselected and luthier invoices remain private.
- Passed: Buyer personal labels and archive preserve the original certificate and transfer evidence.
- Passed: Former owner loses collection access but retains their private archive and original photo.
- Passed: Check the buyer's mobile view, ownership dates and French controls.

## Review or repeat

Local artifacts are in `target/e2e/20261110T084035Z-5f268b49/`: `report.md`, `result.json`, screenshots, role-specific Playwright traces, `server.log` and the copied `maven-tests.log`. These generated artifacts are excluded from Git; the results above are the durable summary. To reproduce:

```sh
./mvnw -o test
.venv-e2e/bin/python tests/e2e/test_passport_journey.py --offline
```

This execution environment supplied an explicit Mockito Java agent to Maven because it restricts JVM self-attachment. The browser required local process and loopback access. See the [browser setup guide](../../tests/e2e/README.md) for installation and isolation details.

## Human beta status

The [three-role session kit](README.md), [English tasks](en/tasks.md), [French tasks](fr/tasks.md), [synthetic files](fixtures/README.md) and [blank session notes](session-notes-template.md) are ready. No participant completion rates, timings or feedback have been collected. Those observations must come from an actual session before drawing usability conclusions.
