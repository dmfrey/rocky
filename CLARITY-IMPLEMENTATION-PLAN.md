# Rocky Clarity visual refresh plan

Give Rocky a consistent enterprise appearance using Clarity styling while retaining React,
existing application behavior, and deployment to Internet-restricted Tanzu environments.
Keep Rocky branding. The target is a Clarity-aligned visual refresh, not full design-system
compliance or an Angular migration.

Implementation branch: `codex/clarity-visual-refresh` in
[trevorputbrese/rocky](https://github.com/trevorputbrese/rocky/tree/codex/clarity-visual-refresh).
Progress and validation results belong in the [work log](docs/clarity-work-log.md).

## Design and technical approach

- Use a dark navy product header, clear sidebar navigation, restrained blue actions, neutral
  surfaces, consistent spacing and typography, and locally bundled SVG icons in place of emoji.
- Use `@clr/ui` CSS and appropriate markup inside existing React components. Clarity documents
  a [CSS-only installation](https://github.com/vmware-clarity/ng-clarity/blob/main/docs/INSTALLATION.md).
  Avoid the older Clarity Core React integration; its
  [repository is archived and unsupported](https://github.com/vmware-clarity/core).
- Resolve Clarity and Tailwind reset, theme, and stylesheet-order interactions on the first
  screen. Centralize reusable styles in `frontend/src/index.css` and shared components; keep
  existing routing, state management, and API contracts.
- Pin the selected package version in the lockfile. Bundle fonts, icons, and CSS with the
  frontend, including required license notices. Runtime rendering must require no public CDN.

## Implementation sequence

### 1 Establish the baseline

Run the existing frontend tests, lint, and production build, and capture the current screens.
Preserve the working deployment and its artifacts. The visual branch starts at upstream
`4ae801a`; local `main` at `2337496` also contains the tested slow-model timeout fix and
deployment documentation. Before rebuilding a backend from this branch, selectively carry
over the timeout source changes, tests, and generic configuration from `8f42117`. Keep private
environment notes out of the public branch.

**Checkpoint:** baseline results recorded and the deployment fix accounted for.

### 2 Build one representative screen

Apply the design foundation to `AppShell.tsx`, `Sidebar.tsx`, `ChatPage.tsx`, and
`EmptyChatPage.tsx`. Include the header, navigation, conversation history, composer, streamed
answer, citations, loading state, and error state. Check both light and dark preferences.

**Checkpoint:** present screenshots or a local preview for visual feedback before extending
the treatment across the remaining screens.

### 3 Apply the shared styling

Standardize buttons, fields, menus, dialogs, alerts, and status badges. Apply them to Documents,
Settings, Login, Register, the consent banner, and archived chats. Retain existing interactions
and validation messages. Keep document status readable through text as well as color.

**Checkpoint:** consistent appearance across all screens, with usable narrow layouts and
visible keyboard focus.

### 4 Verify behavior and offline readiness

Run `npm test`, `npm run lint`, and `npm run build` in `frontend/`. Add or adjust focused tests
only where markup or interaction changes create a meaningful regression risk. If the timeout
fix is carried over, run `GenaiModelConfigurationTest` with Java 25.

Manually exercise login, registration, consent, document upload/replacement/deletion and
processing states, streamed chat and citations, history rename/archive/delete, and settings.
Check keyboard navigation, modal focus and dismissal, labels, contrast, overflow, and both
color schemes. With public network access blocked, inspect browser requests and verify that
all UI assets load from the application. Record failures and their resolutions.

**Checkpoint:** existing workflows pass and the UI makes no public asset requests.

### 5 Package and preview in the lab

Build the frontend on an Internet-connected machine using
`VITE_API_BASE_URL=__RUNTIME_API_BASE_URL__ npm run build`, then package `dist` with the
existing offline workflow. Retain an untouched archive, source revision, and checksums.
The restricted VM should only need the prepared artifacts and CF CLI for deployment.

Use separate preview app names and routes, checking quota, service bindings, backend timeout,
and CORS first. Preserve the working demo and its artifacts as the rollback baseline. Update
the portable deployment instructions with any changed steps, then validate the preview from
the browser inside the lab.

**Checkpoint:** reviewed lab preview, repeatable offline package, and documented rollback.

## Progress tracking

The visual refresh is implemented, tested, and deployed to separate preview apps.
Completed changes, test results, and remaining issues are recorded in the work log. See the
[preview deployment guide](docs/clarity-deployment.md) for packaging and deployment commands.
