# Screenshot provenance

These images show the real Angular UI with wholly fictional data supplied by
`scripts/demo-server.mjs`. No production database, portal responses, school letters,
family records or credentials were used. Example domains are reserved domains.
The translation is a letter written specifically for the demo.

## Reproduce

From the repository root, using Node >=22.22.3:

```bash
cd frontend
npm ci
npm run build
cd ..
node scripts/demo-server.mjs
```

Open `http://127.0.0.1:4317`. The clock is fixed to 13 October 2026. The server
binds to loopback, serves only the compiled frontend and in-memory API fixtures,
and refuses writes. It does not replace the real backend or connect integrations.
Use `SCHOOLAGENT_DEMO_PORT` to select another port if needed.

Desktop captures used a 1440 × 1080 CSS viewport (saved images may be scaled by the
capture tool). Mobile captures used a 390 × 844 viewport; they demonstrate the
responsive layout, rather than a capture from an actual device.

| Image | View |
|---|---|
| `inbox.png` | `/`, All children, information visible |
| `case-detail.png` | `/cases/demo-field-trip` |
| `translation.png` | Same case, Full translation, reader scrolled into view |
| `calendar.png` | `/calendar`, October 2026, 16 October selected |
| `school-settings.png` | `/settings`, School, School portal expanded |
| `family-settings.png` | `/settings`, Family |
| `mobile-inbox.png` | `/` at the mobile viewport |
| `mobile-case.png` | `/cases/demo-field-trip` at the mobile viewport |

Review every replacement image for privacy before committing. Do not reuse
screenshots from a live installation, even if a few names have been blurred.
