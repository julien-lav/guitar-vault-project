# Small beta: owner, buyer and luthier

Use this kit to observe three people trying Guitar Vault with its Help guides. The automated checks verify behaviour; these sessions check whether people can find and understand the actions. Human sessions have **not yet been run**.

## Prepare a local session

From the application directory, with Java 21 available:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=fixtures
```

Open <http://localhost:8082>. This local-only demo uses its own persistent database and session cookie, separate from the ordinary app. Keep it on the local machine; this kit does not set up remote access. Three people can take turns on that machine, each with a separate browser profile. For a remote beta, the operator must first provide an appropriately configured test deployment and test accounts.

| Role | Login email | Exact username |
| --- | --- | --- |
| Owner | `user@guitar-vault.test` | `demo_user` |
| Buyer | `superuser@guitar-vault.test` | `demo_superuser` |
| Luthier | `editor@guitar-vault.test` | `demo_editor` |

The initial demo password is `DemoGuitar2026!`. These are fictitious accounts; use them only on the local demo. If this demo was already loaded, it retains previously changed passwords and data. Do not reset or delete its database to run a session. Use an agreed session label such as `BETA-2026-11-09-A` for new instrument and document titles. The same account in two windows without separate browser profiles will share a session.

Use the [sample files](fixtures/README.md), not real invoices or personal documents. Pick English or French in the app. Give each participant only their corresponding section of [English tasks](en/tasks.md) or [French tasks](fr/tasks.md), plus the session label. Test one phone-sized viewport as well as a desktop. A returning luthier may already have a workshop; update that workshop rather than creating another account.

## Facilitate without coaching

1. Explain that the app is being tested, not the participant. Ask them to say what they expect an action to do.
2. Let participants use `/help` whenever they want. Note when they open a guide and whether it answers their question.
3. Use [session notes](session-notes-template.md) to record completion, time, wrong turns and prompts. If they need a hint, record the exact hint; do not mark that task as independent success.
4. Run the phases in order: luthier prepares workshop → owner prepares → luthier reports → owner proposes → buyer accepts → owner and buyer review privacy. Pause at each handoff until the next role is ready.
5. Before each sharing or transfer action, ask the participant to predict who will see the notes, photos, invoice, report and selected certificate. Record their prediction before checking the result.
6. Ask each person which label or page was least clear, what they expected next, and what they would change first. Use participant IDs in notes, rather than names or email addresses.

A useful planning allowance is 30–45 minutes for the coordinated journey, with extra time for discussion. This is a session budget, not a measured completion time.

## Facilitator verification checklist

These are the expected outcomes, separate from the participant goals:

- [ ] The owner creates one uniquely named instrument with private notes, a photo, an invoice and a certificate.
- [ ] The luthier publishes or finds their workshop. Its professional identity remains **unverified** unless an administrator has separately reviewed it; profile creation must not imply verification.
- [ ] The owner requests an authenticity assessment from that workshop. The luthier sees the assigned request without access to the rest of the owner’s collection.
- [ ] The luthier records a report and a reasoned correction. Both revisions remain available. Their private invoice and explicitly shared evidence have different visibility.
- [ ] The owner selects only the certificate for the buyer. Before acceptance, the buyer sees identifiers and a file count, without selected-file download access or private request text.
- [ ] Acceptance moves the same instrument into the buyer’s collection. The report revisions and shared evidence are readable; the owner’s private invoice, notes and photo stay private.
- [ ] The former owner can find their private archive and original files, without access to the buyer’s private collection.
- [ ] The buyer can rename and archive their certificate personally, restore it and download it. The owner’s labels and original transfer labels remain unchanged.
- [ ] An unused upload can be deleted after confirmation. Report or transfer evidence cannot be deleted; archiving stays available.
- [ ] Participants can find the relevant Help guide in their chosen language on desktop and mobile.

## Decide what to fix

Record an issue as **blocking** if a core task cannot finish, data is lost, or private information reaches another role. Record it as **confusing** if it needs a prompt or causes an incorrect sharing prediction. Record cosmetic suggestions separately. Include reproducible steps, expected/actual behaviour, role, language and viewport, using synthetic data only.

Before inviting more testers, resolve blocking issues and repeat the affected scenario. Agree on fixes for repeated navigation or privacy misunderstandings. Three participants provide observations, not proof of general usability or a production release approval.

## Automated evidence

- [Latest automated test record](automated-results.md)
- [Browser test setup and reports](../../tests/e2e/README.md)
- [User guides](../user-guide/README.md)

Keep completed human-session notes outside the public Help catalog and out of Git unless they contain only anonymised observations approved for sharing. This folder is not included in the website’s Help catalog.
