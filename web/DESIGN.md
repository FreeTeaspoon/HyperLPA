# HyperLPA web design

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

The main navigation follows actual work: Devices, Profiles, Notifications, Group,
and Activity. The page has no sidebar or decorative hero. Device tiles open a
phone's readers; profile tools stay alongside the selected phone and reader.
Rare device edits live in one Manage mode. Identifiers have adjacent copy buttons.

On narrow screens the shell has 20px gutters, fields use 16px text, controls grow
to at least 40px, permissions stack, and tabs scroll within their own row. Dialogs
use the available viewport height and scroll internally. Device names wrap rather
than expanding the page. Theme is applied before React renders, including a hard
refresh in dark mode.

No image gallery or remote image loading is used. The current profile view uses
code-drawn icons and does not display carrier artwork, so image loading transitions
are not part of this implementation.
