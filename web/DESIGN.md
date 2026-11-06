# HyperLPA web design

## Current shared controls, November 2026

The foundation remains ID Vault `07d7092b3286a057fd1397643d63c6e34388c1c6`, with the approved MYTGS Downloads refinements from `a3ee9cd98387bcef14031570416a289354bf4557`. These rules supersede older press, focus, tile-shadow and touch-target measurements below.

- Actions scale to .96 over 150ms ease-out only with no motion preference. Disabled controls, menu triggers and frequent view choices stay still. The original 1px downward press is removed.
- Light cards use a 6% resting outline, 6% contact shadow and 4% diffuse shadow. Interactive hover uses a solid 1px ring-colored outline, with 8% contact and 6% diffuse shadows. Dark cards keep a flat 8% white resting outline and a solid ring-colored hover outline. Hover applies only on hover-capable pointers. Transparent 1px boundaries support forced colors; ordinary padded tiles retain a 16px total inset.
- Menus have 13px outer corners, 4px padding and 8px item corners. Keyboard focus uses a solid 2px outline, offset 3px, with neutral .58 light / .68 dark ring tokens. Menu item focus is inset to avoid clipping. Desktop menu rows match DiDi UID at a 32px minimum height, 4px 8px padding, 8px gaps and 14px/20px text.
- Dropdowns, submenus and select panels use DiDi UID's exact outline and shadow: `0 0 0 1px color-mix(in oklch, var(--foreground), transparent 90%), 0 4px 12px oklch(0 0 0 / 0.08)`. The same expression applies in both themes, alongside the transparent 1px forced-colors border. This replaces the default Tailwind shadows and restores the visible foreground/10 outline.
- Dialogs pair the foreground/10 outline with DiDi UID's `0 8px 30px oklch(0 0 0 / 0.08)` shadow.
- Toolbar actions and menu items have 44px touch targets. Small-screen fields use 16px text. Hover feedback is limited to hover-capable pointers.
- Preserve IBM Plex, neutral colors, compact desktop controls, 100ms dialogs and 200ms copy glyph feedback. Resting input and unchecked-checkbox boundaries retain .9 light / white 14% dark. These subtle boundaries are a deliberate preference, without a claim of complete WCAG conformance.
- Toolbar menus are non-modal; real dialogs keep focus trapping, background scroll locking and content through exit. Shadow-backed boundaries remain visible in forced colors.

Only the web dashboard adopts these rules. Phone management, recovery, profiles, permissions and relay behavior are preserved. The root scrollbar gutter is paired with Radix scroll-lock compensation. While a dialog is open, the root locks scrolling and the body clips overflow without becoming a scroll container, so the sticky top bar stays in place on scrolled pages.

Dropdown appearance verification, 4 November 2026: the production build passed. The built Appearance menu's computed outline, shadow, radius, padding and colors matched DiDi UID's current source CSS in light/dark at 1280px and 390px. Escape restored trigger focus, and forced colors retained a visible border in both themes. Synthetic submenu/select panels also matched the shared shadow, radius and border. This check used the setup page without private account data.

Verification for this rollout: the production build, TypeScript check, eight unit tests and both existing Playwright workflow tests pass. The browser workflow uses a synthetic local relay and phone, covering pairing, profiles, recovery, permissions, narrow layouts and axe checks. Shared-control checks also cover focus, press feedback, reduced motion, touch targets, forced colors and menu/dialog scroll stability. Dialog measurements cover short and overflowing pages at scroll position 300px, a measured 15px classic scrollbar, and frame-by-frame bounds through exit in both themes at 1280px and 390px. No private phone or carrier account was used.

The dashboard uses compact controls, neutral colors, and locally bundled fonts.

## Shared rules

- IBM Plex Sans Variable for interface text; IBM Plex Mono for identifiers, relay
  addresses, invitations, and recovery material. Fonts are bundled locally.
- Neutral OKLCH light/dark tokens are in `src/theme.css`.
- A 1024px shell, 56px header, 32px desktop controls, 10px button radius, 14px
  outlined records, 16px/stroke-2 Lucide toolbar icons, and restrained typography.
- shadcn components use Radix for dialogs, menus, tabs, selectors, and checkboxes.
  Generated defaults were adapted to the dashboard's geometry and states.
- Dialogs retain their content during the 100ms enter/exit animation. Copy glyphs
  use a 200ms transition; action buttons use the .96 scale press described above.
  Reduced motion suppresses those effects. The scrollbar gutter stays reserved during overlays.
- Subtle resting input and checkbox boundaries are deliberate. Keyboard focus,
  invalid, and checked states remain distinct.

## Dashboard adaptations

The main navigation follows actual work: Devices, Profiles, Notifications, History,
Tools, Group, and Activity. The page has no sidebar or decorative hero. Device tiles
open a phone's readers; profile tools stay alongside the selected phone and reader.
History exposes the phone's eUICC notification audit trail. Tools groups eUICC
details, discovery, safe preference toggles, and destructive reset behind the same
reader selection. Batch downloads use the same compact modal form and keep one
operation in flight.
Rare device edits live in one Manage mode. Identifiers have adjacent copy buttons.

On narrow screens the shell has 20px gutters, fields use 16px text, controls grow
to at least 40px, permissions stack, and tabs wrap onto additional rows. Tab focus
outlines sit inside the targets and appear for keyboard navigation. Pointer focus
stays free of outlines through window deactivation and reactivation; keyboard
input outside the group restores keyboard focus on re-entry. The selected 2px
underline stays flat and independent of the focus outline. Dialogs
use the available viewport height and scroll internally. Device names wrap rather
than expanding the page. Theme is applied before React renders, including a hard
refresh in dark mode.

No image gallery or remote image loading is used. The current profile view uses
code-drawn icons and does not display carrier artwork, so image loading transitions
are not part of this implementation.

Navigation verification, 5 November 2026: the production build and focused headed Chromium regression pass. A synthetic local group covers both themes, reload and first pointer click, null-target focusout, modifier input, actual document deactivation/reactivation with focus emulation disabled, stable underlines, keyboard exit/re-entry and arrow activation. Tab and document bounds are checked at 320px, 390px, 640px and 1280px. The actual focus-loss branch runs with `--headed`; headless Chromium retains window focus. No private group or phone data was used.


## Interface audit fixes, November 2026

Keyboard-focused tab panels and navigation headings have visible inset outlines.
Device-card and brand navigation focus the destination heading, and each view has
a document title. Tab changes preserve the library's keyboard behavior. Appearance
exposes its selected theme through radio-menu semantics, with DiDi UID's trailing
16px check icon.

Field validation marks and describes the invalid input, focuses it after submit,
and clears the error on edit. Network errors remain form-level alerts. eUICC
history deletion requires a destructive confirmation that names its target, and
confirmation buttons name their action. Profile rename stays in the profile's own
menu; Tools no longer chooses the first sorted profile under a misleading label.

Long phone and reader names wrap within the available width. Dark fields use the
input token at 30%; resting borders retain their intentionally subtle values.
Toasts use the shared panel colors, IBM Plex font, 13px corners and card shadow in
explicit and system themes. Dialog dismissal restores focus without scrolling.

Regression coverage uses synthetic records plus the local relay/phone workflow.
It checks field associations and focus, cancellation before history deletion,
all seven tabs with long labels at 320px, 390px and 1280px in both themes,
radio semantics, computed menu/dialog/toast styles and automated axe checks.
The overlay test launches Chromium without its hidden-scrollbar flag, asserts a
nonzero classic scrollbar before locking, and compares the header, shell and
right-side action through the closing animation. It covers pointer and keyboard
opening, background wheel locking, non-modal menu scrolling and focus return at
0px and 300px scroll positions, 390px and 1280px, in both themes.

Subtle resting field borders remain below the usual 3:1 non-text contrast target.
The audit measured approximately 1.26:1 in light mode and 1.46:1 in dark mode
before the dark fill correction. Preserving these borders is a deliberate style
choice; passing axe checks does not establish complete WCAG conformance.

Validation: the TypeScript/production build, eight unit tests and seven Playwright
tests pass. The compiled shared controls also match DiDi UID's computed menu and
dialog measurements in both themes at desktop and mobile widths.
