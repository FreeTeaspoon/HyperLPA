# HyperLPA web design

## Current shared controls, November 2026

The foundation remains ID Vault `07d7092b3286a057fd1397643d63c6e34388c1c6`, with the approved MYTGS Downloads refinements from `a3ee9cd98387bcef14031570416a289354bf4557`. These rules supersede older press, focus, tile-shadow and touch-target measurements below.

- Actions scale to .96 over 150ms ease-out only with no motion preference. Disabled controls, menu triggers and frequent view choices stay still. The original 1px downward press is removed.
- Light cards use a 6% outline, 6% contact shadow and 4% diffuse shadow. Interactive hover uses 8%, 8% and 6%. Dark cards keep a flat 8% white outline, rising to 13% on hover-capable pointers. Transparent 1px boundaries support forced colors; ordinary padded tiles retain a 16px total inset.
- Menus have 13px outer corners, 4px padding and 8px item corners. Keyboard focus uses a solid 2px outline, offset 3px, with neutral .58 light / .68 dark ring tokens. Menu item focus is inset to avoid clipping.
- Toolbar actions and menu items have 44px touch targets. Small-screen fields use 16px text. Hover feedback is limited to hover-capable pointers.
- Preserve IBM Plex, neutral colors, compact desktop controls, 100ms dialogs and 200ms copy glyph feedback. Resting input and unchecked-checkbox boundaries retain .9 light / white 14% dark. These subtle boundaries are a deliberate preference, without a claim of complete WCAG conformance.
- Toolbar menus are non-modal; real dialogs keep focus trapping, background scroll locking and content through exit. Shadow-backed boundaries remain visible in forced colors.

Only the web dashboard adopts these rules. Phone management, recovery, profiles, permissions and relay behavior are preserved. The root scrollbar gutter is paired with Radix scroll-lock compensation.

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
  use a 200ms transition; buttons move down 1px when pressed. Reduced motion
  suppresses those effects. The scrollbar gutter stays reserved during overlays.
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
to at least 40px, permissions stack, and tabs scroll within their own row. Dialogs
use the available viewport height and scroll internally. Device names wrap rather
than expanding the page. Theme is applied before React renders, including a hard
refresh in dark mode.

No image gallery or remote image loading is used. The current profile view uses
code-drawn icons and does not display carrier artwork, so image loading transitions
are not part of this implementation.
