# Native UI review

Reviewed 2026-10-01. Hearth extends the Minecraft 26.2 interface in **Operate** mode: players choose a shared world, manage its rules and access, or add one. The visual authority is Minecraft's existing font, panorama, controls and dialogs. This review records that ordinary extension; the incumbent implementation remains the styling source.

## Native styling evidence

The six `src/main/java/hearth/mc/*Screen.java` files, including the nested Add World screen, extend Minecraft `Screen` and use `Button`, `EditBox`, `StringWidget` and `MultiLineTextWidget`. They inherit the native background and control rendering. All fourteen final captures show the dimmed panorama, pixel font, gray textured rectangular buttons, white labels and native disabled states. Titles use the font's measured width to center their widget.

Status text uses Minecraft `ChatFormatting`: gray for checking or an idle world, green for a current player, gold for a stale or unsaved session, and red for storage failure. The message also names the state. There is no new decorative layer to document as a separate design system.

## Capture matrix and scaling

Evidence root: `/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/managed-peer-v2/`.

Both `ui-confirm-large/screenshots/` and `ui-confirm-small/screenshots/` contain the following complete, inspected captures:

| Filename | Screen | State shown |
| --- | --- | --- |
| `hearth-ui-0.png` | Shared worlds | One idle world, Play and Details |
| `hearth-ui-1.png` | World details | Creator actions and local network help |
| `hearth-ui-2.png` | World settings | Loaded settings, Save and Cancel |
| `hearth-ui-3.png` | World access | One member, Ban, IP bans and Back |
| `hearth-ui-4.png` | History and saves | Saved sessions, save details and pagination |
| `hearth-ui-5.png` | Add a world | Invite field and no eligible local worlds |
| `hearth-ui-6.png` | Create a shared world | Creator explanation and GitHub sign-in action |

The framebuffer is 1708 × 960 in both sets. `large` uses GUI scale 2, with an 854 × 480 logical viewport; `small` uses GUI scale 4, with a 427 × 240 logical viewport. These are two native GUI densities at one window size. Controls and text scale through Minecraft. The small logical viewport displays fewer history rows and an enabled Next control.

`src/gametest/java/hearth/mc/UiReviewDriver.java` checks title centering, Button/EditBox bounds, control overlap and translated control labels before each capture. Both `ui-confirm-large.log` and `ui-confirm-small.log` reach `[ui-test] All native screens checked` after all seven captures. Visual inspection confirms the files show their named screens and readable, separated content. This evidence covers these viewports and the shown Russian locale.

Additional error-state evidence: `ui-member-error/screenshots/hearth-ui-0.png` shows World access at 1708 × 960 and GUI scale 4. The visible **Не удалось загрузить участников** (Could not load members) message is followed by a native **Повторить** (Retry) button; IP bans and Back remain separated and reachable. `ui-member-error.log` records the WorldAccessScreen capture and completes the bounds and title-centering checks with `[ui-test] All native screens checked`. This capture was produced without production UI changes.

## Interaction labels and creator confirmations

The translation files `en_us.json` and `ru_ru.json` provide named actions. Shared worlds uses **Play** / **Играть** and **Details** / **Подробнее**; Play selects hosting or joining from live status. Add World separates pasting an invite from sharing or creating a local world. World details exposes **Invite**, **History and saves**, creator **World settings** and **World access**, plus the connection choice. Settings express On/Off in their labels and provide Save/Cancel. History and access use Previous/Next with unavailable navigation disabled. Back returns to the prior screen.

Source inspection confirms native `ConfirmScreen` prompts for member Ban/Unban, approving the current mod set, and creator Restore. The prompts identify the affected member, mod requirements, or selected save. Restore's copy asks the creator to stop the current session and explains the new history entry and retained rules. Settings and access navigation, and the Restore action, are gated by `HearthClient.isOwner(entry)`. These dialogs and permission enforcement were inspected in source; the seven-screen capture matrix does not exercise their outcomes.

## Empty, loading and retry behavior

The Add World captures show **No local worlds** with the selector and sharing action disabled, while invite entry and native Create New World remain available. Source also supplies a shared-world empty message with a next step, **Invite a friend to see them here** for an empty member list, and **No saved sessions yet** for empty history.

History shows Checking while loading, then an explicit failure message and Retry on error. World access shows **Could not load members** with Retry if member loading fails; the additional error-state capture verifies both are visible and within bounds, and source confirms Retry rebuilds the member list. Shared-world status distinguishes checking, idle, stale and unreachable storage; Play remains disabled until a status is available. Settings disables Save until loaded and reports load failures through native `AlertScreen`. GitHub errors also return through a native alert. Empty history and the remaining error states are source-verified and outside the visual capture evidence. The error-state screenshot establishes the retry action's presentation; it does not demonstrate a successful retry after service recovery.

## Review scope

The final matrix verifies native styling, title alignment, the save-details label, the disabled empty selector and the layout checks above. The earlier finish verdict scored title alignment, the history label and the empty selector resolved in both viewports. The finish reviewer subsequently scored the member-load error resolved from the additional native capture. Final disposition: ship, scoped to all four listed fixes.

This visual review does not prove GitHub OAuth completion, Steam connectivity, Windows compatibility, or multiplayer gameplay. The nested game-rule, IP-ban, save-detail and confirmation screens were inspected in source but are outside this capture matrix. Pre-existing drift and untested states are not promoted into new design rules or repaired by this documentation pass.
