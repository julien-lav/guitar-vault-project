# Automated beta baseline

Recorded on 2026-11-12 against the Tailwind/daisyUI interface, including vintage shops, workshop repairs and additional-work approvals. These are automated checks; human usability sessions have not yet been run.

| Check | Result |
| --- | --- |
| Full Maven regression suite | 333 passed; 0 failures; 0 errors; 1 skipped out of 334 tests |
| Chromium passport and vintage-shop journey (Google Chrome) | 27 steps passed |
| Browser errors recorded | 0 |
| Human usability sessions | Not yet run |

The skipped test is the optional Mailpit integration. SMTP delivery was not checked. The full suite includes private dashboard previews and participant-scoped tasks and visits, former-owner photo exclusion, vintage-shop publication, inventory, photos, prices and stock, contact and booking privacy, concurrency, existing notification-schema upgrades, workshop repairs, additional-work approvals, ownership transfers, and bilingual Help guides.

## Browser coverage

The journey used separate owner/shop-operator, buyer, luthier, administrator and anonymous browser sessions, normal password login and rendered forms. Its app ran on a temporary loopback port with an isolated in-memory database, then shut down.

- Passed: Start an isolated application and seed the standard demo accounts.
- Passed: Log in as two members and a luthier using separate browser sessions.
- Passed: Read public Markdown guides, follow guide links, download source and switch language.
- Passed: Publish the luthier workshop through its form.
- Passed: Luthier enables weekly availability and blocks a day off.
- Passed: Musician and luthier privately arrange and confirm an appointment with a counter-proposal.
- Passed: Owner creates an instrument with private notes, history and a real PNG upload.
- Passed: Review the redesigned dashboard at desktop, tablet and mobile widths, keyboard passport tabs, direct section links, mobile menu and JavaScript-free fallback.
- Passed: Upload a private invoice and certificate; both are private until explicitly selected.
- Passed: Manage personal labels, filter archived uploads, restore and delete an unused file.
- Passed: Vintage shop publishes an instrument with asking price and an explicit public photo while collection details stay private.
- Passed: Buyer contacts vintage shop about a listing and privately agrees a viewing with its operator.
- Passed: Shop adds a new instrument passport with price and manages reserved and sold stock.
- Passed: Owner requests an authenticity assessment; luthier receives only the selected request.
- Passed: Workshop records intake, revises a quote, receives approval and tracks work.
- Passed: Additional work pauses repair; owner refuses then approves revised terms without losing the original agreement.
- Passed: Luthier records an assessment and then a correction without replacing the first revision.
- Passed: Owner is notified that the instrument is ready and workshop records collection.
- Passed: Luthier keeps an invoice private and explicitly shares evidence with future report readers.
- Passed: Owner proposes a transfer; buyer opens the notification and sees only basic instrument details.
- Passed: Buyer accepts; the same instrument and both report revisions follow the passport.
- Passed: Shop listing disappears after ownership transfer while the original private enquiry stays accessible to its participants.
- Passed: Buyer downloads only the selected certificate; unselected and luthier invoices remain private.
- Passed: Buyer personal labels and archive preserve the original certificate and transfer evidence.
- Passed: Former owner loses collection access but retains their private archive and original photo.
- Passed: Check the buyer's mobile view, ownership dates and French controls.
- Passed: Cancellation releases a reserved slot and unblocking restores a working day.

## Vintage-shop safeguards

Only published shops and listings become public. Listings use explicit sale descriptions, asking prices and separately uploaded public photos; private collection notes, serial numbers, photos, documents and reports remain private. An operator can create an instrument passport or list one they own. Sold stock leaves the catalogue, and ownership transfer hides the former shop’s listing and public photo. A conversation preserves the listing name and asking price at the time of the enquiry and remains private after sale or unpublication.

The tests verify that two overlapping viewing confirmations cannot both succeed, two live listings for the same passport cannot be published simultaneously, and two first-time profile submissions cannot overwrite one another. Confirmed visits last 30 minutes. Time zones and daylight-saving validation are shared with workshop appointments, whose regression checks also pass.

## Workshop safeguards

The repair workflow preserves quote and approval history. Additional-work proposals pause repairs without replacing the current agreement. Approval applies only to the current proposal; refusal preserves the agreement and keeps work paused. The luthier can revise, cancel, or explicitly resume only the existing approved scope with an explanation. Progress, readiness and collection are blocked while paused. Existing H2 stage-enum upgrades and simultaneous approval versus revision are tested. Active repairs block ownership transfer even after a report has been signed.

## Review or repeat

Local artifacts are in `target/e2e/20261112T154757Z-ab80d2e4/`: `report.md`, `result.json`, screenshots, role-specific Playwright traces, `server.log` and `maven-regression.log`. Interface screenshots include `02h-dashboard-desktop.png`, `02i-dashboard-mobile.png`, `02j-passport-notes.png` and `08-buyer-mobile.png`. Shop screenshots include `02e-vintage-shop.png`, `02f-public-shop-listing.png` and `02g-shop-visit-mobile-fr.png`. Generated artifacts are excluded from Git; this document is the durable summary. To reproduce:

```sh
./mvnw -o test
.venv-e2e/bin/python tests/e2e/test_passport_journey.py --offline
```

This execution environment supplied an explicit Mockito Java agent because it restricts JVM self-attachment. The browser used the installed Google Chrome executable and required local process and loopback access. See the [browser setup guide](../../tests/e2e/README.md) for installation and isolation details.

## Human beta status

The [three-role session kit](README.md), [English tasks](en/tasks.md), [French tasks](fr/tasks.md), [synthetic files](fixtures/README.md) and [blank session notes](session-notes-template.md) are ready. No participant completion rates, timings or feedback have been collected. Those observations must come from an actual session before drawing usability conclusions.
