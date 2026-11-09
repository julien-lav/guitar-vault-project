# Guitar Vault user guides / Guides utilisateurs

Read these guides directly in Git or open **Help & guides / Aide et guides** at `/help` in the application. Use the website’s language menu to switch languages. Sign-in is not required to read the help pages.

| Topic / Sujet | English | Français |
| --- | --- | --- |
| First steps / Premiers pas | [Getting started](en/getting-started.md) | [Premiers pas](fr/getting-started.md) |
| Collection and wishlist | [Your instruments](en/instruments.md) | [Vos instruments](fr/instruments.md) |
| Documents and archives | [Documents](en/documents.md) | [Documents](fr/documents.md) |
| Luthier workshops and reports | [Luthiers](en/luthiers.md) | [Luthiers](fr/luthiers.md) |
| Ownership handover | [Transfers](en/transfers.md) | [Transferts](fr/transfers.md) |
| Bands, shows and studios | [Bands and studios](en/bands-and-studios.md) | [Groupes et studios](fr/bands-and-studios.md) |
| Common questions | [FAQ](en/faq.md) | [Questions fréquentes](fr/faq.md) |

## Maintaining the guides

- Keep instructions consistent with the current application. Explain visible actions and privacy choices; keep deployment and implementation details in the application README.
- Update both language versions when behaviour changes. Each guide starts with one `#` title and a short introductory paragraph, used on the Help index.
- Use normal Markdown headings, paragraphs, lists, emphasis and links. Raw HTML is displayed as text. JavaScript links are stripped by the renderer.
- Link between guides with relative filenames such as `[Documents](documents.md)`. These links work in Git and are rewritten to the matching Help route on the website, preserving the selected language. Use a full HTTPS URL for an external link.
- The catalog in `HelpService.SLUGS` explicitly lists public guides. To add a topic, add its filename in both language directories and register its slug there. Files outside that catalog are never exposed by Help routes.
- Maven copies `en/*.md` and `fr/*.md` to `help/` in the application classpath. Rebuild/restart the app after editing; packaged JARs include the guides and do not need a checkout or a working-directory path at runtime.
- Each guide offers a Markdown download with the same source text. No Git hosting URL is hard-coded, so this works in local checkouts and private repositories.

From the application directory, run `./mvnw test` to check the catalog, languages, rendering, source downloads and access rules. The browser walkthrough also checks navigation, guide links and mobile layout.
