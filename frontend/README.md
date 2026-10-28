# Rocky frontend

React, TypeScript, Vite, React Router, and TanStack Query provide the existing application
behavior. The visual refresh uses Clarity UI 18.4.0 CSS with Tailwind utilities and a small
set of shared React components.

## Development and verification

Use Node 26 and npm:

```bash
npm ci
npm run dev
npm test
npm run lint
npm run build
```

The development server proxies `/api` to `localhost:8080`. The production frontend is a
static bundle; no Node server is needed on Cloud Foundry. Follow the
[preview deployment guide](../docs/clarity-deployment.md) to produce a portable offline bundle.

## Styling conventions

`src/index.css` defines the light/dark palette, responsive application shell, shared controls,
and page layouts. Clarity CSS is placed between the reset and utility cascade layers; application
rules own the layout and theme. Vite removes the imported charset declaration before final CSS
minification, because a charset declaration cannot be nested inside a cascade layer.

Use `btn` / `btn-primary`, `form-label` / `form-control`, `panel`, and the existing shared React
components for consistency. Retain labels, focus indicators, status text, and error states when
changing markup. Dialogs and menus implement their interaction in React; CSS alone does not
supply keyboard behavior. Navigation collapses below 640 pixels, and the theme follows the
browser's color-scheme preference.

Clarity's Metropolis fonts are embedded in its stylesheet. `Icon.tsx` supplies inline SVG icons
and the Rocky mark. The distribution includes `public/CLARITY-LICENSE.txt` with the upstream
Clarity and font licenses. Keep new UI assets local; do not introduce CDN imports.

See "Clarity styling" in the root `CLAUDE.md` for the decisions behind the styling setup.
