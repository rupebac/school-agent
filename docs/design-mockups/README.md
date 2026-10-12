# Redesign mockups (not applied)

Static HTML mockups to choose a new look for the web app. They use fake data
(`data.js`) and shared icons (`icons.js`). Nothing here is wired into
`frontend/`.

Open `index.html` in a browser (directly from disk works; Google Fonts need
internet). It shows all four directions side by side, desktop and phone,
inbox and case detail. Each variant can also be opened on its own; append
`?screen=detail` for the case detail screen.

| File | Direction |
|---|---|
| `a-paper.html` | A · Paper — calm, editorial serif, grouped by urgency |
| `b-control.html` | B · Control Center — sidebar + list + detail, app-like on phones |
| `c-timeline.html` | C · Timeline — agenda-first, drawer/bottom-sheet detail |
| `d-night.html` | D · Night board — dark, one column per child, spotlight card |
| `e-control-kids.html` | E · Combined — B layout + per-child lanes, icon rail, calendar screen (`?screen=calendar`, `?view=together`) |
