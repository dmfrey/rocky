# Rocky Clarity visual refresh work log

Plan: [Clarity implementation plan](../CLARITY-IMPLEMENTATION-PLAN.md).
Branch: `codex/clarity-visual-refresh`.

## 2026-10-28 Planning completed

- Confirmed the scope: retain React and refresh the presentation using Clarity styling.
- Verified the existing fork and tracking branch. The working checkout now uses the visual
  branch; the tested deployment history remains on local `main` at `2337496`.
- Reviewed the frontend structure, build scripts, and offline bundle workflow. Selected the
  documented `@clr/ui` CSS approach; Clarity Core is archived and unsupported.
- Added the implementation plan, this log, and a README link. Defined an early header,
  sidebar, and chat preview, followed by shared controls, remaining screens, regression checks,
  and offline packaging.
- Identified the existing slow-model timeout fix as a prerequisite when rebuilding the backend
  from this branch. It has not yet been copied. Its source files are `GenaiProperties.java`,
  `GenaiModelConfiguration.java`, and `GenaiModelConfigurationTest.java` under
  `backend/src`; the optional manifest setting is `APP_GENAI_CHAT_TIMEOUT`.
- Validation: checked repository-relative documentation links and `git diff --check`.
  Application tests were not run because this step changes documentation only.

Next: establish the frontend baseline, then build the representative screen described in the
plan. No application code, dependency, deployment, or infrastructure changes were made during
this planning step.

## Entry format for implementation

For each completed phase, append its date, changes and affected files, decisions, tests or
manual checks with actual outcomes, remaining issues, and the next checkpoint. Link screenshots
or preview artifacts when available. Keep credentials and environment-specific operational
details outside this public log.

## 2026-10-28 Implementation and baseline verification

- Baseline: Node 26.12.1; all 31 existing frontend tests and the production build passed.
  Lint reported two existing warnings. npm reported six existing dependency advisories
  (three moderate, three high); adding Clarity did not change that count.
- Added exact dependency `@clr/ui` 18.4.0, explicit CSS layers, shared application styles,
  locally embedded typography, inline SVG icons, and the upstream MIT/font license notice.
  Tailwind utilities remain available. Vite handles final CSS minification after removing
  the imported charset declaration that cannot be nested inside a cascade layer.
- Refreshed the product header, responsive sidebar, welcome view, chat, document table,
  settings, authentication, consent, status labels, menus, and confirmation dialogs. Added
  loading and error presentation where the refreshed screens need it. Corrected the sidebar
  active-link callback while updating its markup.
- Menus now support arrow keys, Home/End, Escape, and focus restoration; their placement
  avoids clipping in scrolling navigation. Dialogs focus Cancel initially, contain Tab
  navigation, restore focus, and associate their description with the dialog.
- Carried only the generic timeout configuration and its tests from the tested local
  deployment branch. All six targeted Java tests passed on an actual rerun with Java 25.
- All 35 frontend tests passed, including four new regression tests. Lint now has only the
  existing AuthContext Fast Refresh warning. Captured representative desktop, dark, and
  mobile screenshots using sample data. Visual review identified and corrected dark-mode
  text contrast and an off-screen table accessibility label causing mobile overflow.
- Added a separate preview manifest, example variables, and
  [offline preview deployment instructions](clarity-deployment.md). The two preview apps are deployed; final live browser results will be recorded below.


## 2026-10-28 Browser and offline asset verification

- Automated Chromium checks covered seven screens in both light and dark modes: overview,
  chat, documents, settings, login, registration, and consent. Axe WCAG 2 A/AA and WCAG 2.1 AA
  rules reported zero violations across all 14 views. This is a bounded automated check, not
  a full accessibility certification.
- Keyboard checks passed for menu navigation and dialog focus containment. Mobile navigation
  closed correctly after selecting a destination; the document table scrolls within its
  container at a 390-pixel viewport without widening the page.
- Browser checks blocked requests outside the local app origin and observed zero external
  requests and zero JavaScript errors. The production CSS contains no external asset URLs,
  and the frontend archive includes the Clarity license notice. This verifies offline UI
  assets; it is not a new foundation-wide air-gap rehearsal.
- The final production build passes without CSS warnings. All 35 frontend tests passed again
  after the visual fixes. The remaining lint warning concerns the existing AuthContext export.
- The six baseline npm advisories concern Vite/Vitest and their build/test dependencies;
  package upgrades are outside this visual refresh. The CF frontend runs the built static
  bundle, not the Node development server.

## 2026-10-28 Live deployment verification

- Deployed separate preview backend and frontend apps with the existing database and model
  service bindings. Raised the rehearsal org's route quota from 32 to 34 for these two routes;
  the original apps and routes remain running. Preview and original apps share application data.
- Verified registration, consent, login, upload/embedding, the real model's expected answer
  and matching source citation, conversation persistence, rename, archive/unarchive, profile
  changes, and deletion of the temporary chat and document.
- The first browser script saw the expected answer but hit its own 20-second wait limit while
  waiting for the composer to re-enable. Resumed using the saved conversation and verified the
  persisted answer and citation. Slow internal-model response/title generation remains a
  deployment characteristic; no model-performance change is part of this refresh.
- Live logout/login testing exposed an existing session bug: the server invalidated consent
  on logout, but the frontend kept the old acknowledgment and the next login received HTTP
  428. Updated `AuthContext` to reset consent and remove cached user content on logout, with
  an integration regression test. All 36 frontend tests, lint (one existing warning), and
  the production build pass. The updated bundle is deployed for final account verification.

## 2026-10-28 Completion

- Final deployed retest passed password change, logout, renewed consent, login with the new
  password, document replacement and cleanup, and mobile navigation. The browser blocked
  public destinations and observed zero external requests and zero JavaScript errors.
- The preview backend reports `UP`; both preview and original frontend routes return HTTP
  200. The original backend also remains `UP`.
- Implementation and deployment checks are complete. The connected lab was used for the live
  smoke test; a full network-isolated foundation rehearsal was not repeated in this change.
- Screenshots, detailed test logs, and temporary test-account credentials are under the ignored
  local `build/clarity/` directory. Credentials are excluded from Git and release archives.
  Temporary test documents and conversations were deleted; the test account remains available
  for follow-up verification.
- Portable release contents are the committed source, backend JAR, untouched frontend archive,
  source revision, and checksums. See the deployment guide for reproducing them and rolling
  back to the original app route. Existing development-tool advisories remain documented above.
