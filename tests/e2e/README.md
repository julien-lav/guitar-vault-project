# Passport browser walkthrough

This test follows one instrument through a complete journey in Chromium:

1. Sign in as an owner, a buyer, and a luthier, using separate browser sessions.
   Read public Help guides without signing in, follow Markdown links, download the exact source, switch language and check mobile layout.
2. Publish the luthier's workshop through its form, enable working hours and block a day, then arrange a private appointment with a time zone, counter-proposal, confirmation and follow-up message. Check French mobile layout and deny access to the buyer and administrator, including after the later transfer.
3. Create an instrument with private notes/history, a PNG photo, recording and concert entries.
4. Upload a private invoice and a certificate, then request an authenticity assessment; record a report and a correction as the luthier.
5. Attach private luthier evidence and explicitly shared report evidence, then send a transfer proposal and open it through the buyer's notification.
6. Select only the certificate, accept, and verify that the same instrument, both report revisions and shared evidence reach the buyer. The buyer downloads the certificate through the UI; its bytes are checked.
7. Verify the former owner's archive, including the original photo bytes and unselected invoice, and deny access to the buyer, luthier, and an administrator.
8. Edit personal document labels, filter archived files, restore and confirm deletion of an unused upload; verify a buyer’s archive and personal labels preserve access and the owner’s original labels.
9. Cancel an appointment to free its reserved slot, unblock a working day and check the mobile slot picker.
10. Check mobile layout and French controls, plus browser JavaScript errors and server errors.

The test drives rendered forms and normal password login. It uses no mocked authentication, database writes, or direct domain-service calls to perform the scenario. The application's standard demo loader supplies only the initial demo accounts and sample data.

## Install

From `guitar-vault/`, with Java 21 and Python 3.10+ available:

```sh
python3 -m venv .venv-e2e
.venv-e2e/bin/python -m pip install -r tests/e2e/requirements.txt
.venv-e2e/bin/python -m playwright install chromium
```

If Chromium needs Linux system libraries, Playwright's installer reports the missing prerequisites. An already installed Chrome can instead be used with `--browser-executable /path/to/google-chrome`.

## Run

```sh
.venv-e2e/bin/python tests/e2e/test_passport_journey.py
```

Optional flags:

```sh
# Use cached Maven dependencies only.
.venv-e2e/bin/python tests/e2e/test_passport_journey.py --offline

# Watch the browser on a desktop with a display available.
.venv-e2e/bin/python tests/e2e/test_passport_journey.py --headed
```

A browser or loopback restriction in an execution sandbox may require permission to run this command outside that sandbox. There is no need to weaken the application security configuration.

The command exits **0 on success** and **1 on failure**. It stops its own app process group after success, failure, or interruption. Mailpit is unnecessary. This browser test is separate from `./mvnw test`; run both when changes affect the complete passport journey.

## Isolation

The runner chooses a free loopback port, starts Spring Boot with the `fixtures` profile and a unique **in-memory H2 database**, and disables loading the local `config/application.properties`. Its database and session cookie are distinct from the personal and persistent demo instances. It does not accept an external app URL. The browser blocks third-party requests. Database contents disappear when the test app stops; repeat runs begin with a clean fixture set.

| Browser session | Fictitious account | Purpose |
| --- | --- | --- |
| Owner | `user@guitar-vault.test` / `demo_user` | Creates the instrument, requests work, proposes transfer |
| Buyer | `superuser@guitar-vault.test` / `demo_superuser` | Accepts transfer and reads the passport |
| Luthier | `editor@guitar-vault.test` / `demo_editor` | Publishes a workshop and records two report revisions |
| Administrator | `admin@guitar-vault.test` | Must also be denied access to private archives |
| Anonymous | None | Must be redirected to login for a private photo |

The isolated demo accounts use `DemoGuitar2026!`. Professional verification is deliberately left **unverified**; creating a workshop must not implicitly verify it.

## Results to review

Each run prints a new directory under `target/e2e/` containing:

- `report.md`: step results with screenshot links.
- `result.json`: machine-readable status, timings, errors and screenshots.
- Numbered screenshots of the workshop, original instrument, report versions, proposal, buyer's passport, private archive, document management and archive search, and mobile screens.
- A Playwright trace ZIP for each browser session.
- `server.log`, plus failure screenshots when a browser step fails.

Open a trace locally with:

```sh
.venv-e2e/bin/python -m playwright show-trace target/e2e/<run>/owner-trace.zip
```

Use the screenshots or a headed run to review whether the privacy explanations, report corrections, ownership history, and next actions are understandable. Assertions check behavior and a mobile overflow condition, unchanged report timestamps, matching ownership-period boundaries and document download permissions; they do not replace human review of visual quality or accessibility.

## Human beta sessions

The [three-role beta kit](../../docs/beta/README.md) supplies participant tasks, synthetic uploads and an observation template. Browser assertions and screenshots do not replace observations from real users. Keep human-session outcomes separate from the automated run results.
