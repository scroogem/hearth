# Native UI review

Updated 2026-10-06 for alpha.4, represented by code commit `f5aa7de`. Hearth extends the Minecraft 26.2 interface in **Operate** mode: players choose a shared world, inspect their role and rules, manage access, or add a world. Minecraft's existing font, panorama, controls and dialogs remain the visual authority. Alpha.4 refines invite transfer with optional text-file export and native file-drop import while preserving paste. The alpha.3 and alpha.2 evidence is retained below as historical proof.

## Native styling evidence

The six `src/main/java/hearth/mc/*Screen.java` files and their nested screens extend Minecraft `Screen` and use `Button`, `EditBox`, `StringWidget` and `MultiLineTextWidget`. They inherit native background and control rendering. All 14 alpha.4 originals and the previous 32 alpha.3 originals were inspected: they show the dimmed panorama, pixel font, gray textured rectangular buttons, white labels, native field outlines and disabled states. Titles are centered using their widget width; world titles are capped at 300 logical units with native truncation. Forms generally use a centered 300-unit column and 20-unit-high controls, while the shared-world list fits the viewport width. File-transfer failures use the native `AlertScreen` layout.

Status text uses Minecraft `ChatFormatting`: gray for checking or an idle world, green for a current player, gold for a stale or unsaved session, and red for storage failure. The message also names the state. There is no new decorative layer to document as a separate design system.

## Alpha.4 capture matrix and scaling

Current native originals: `/Users/maks/Documents/hearth/.impeccable/review/alpha4/`. Each filename below exists in both `large/` and `small/`, giving 14 captures. All originals were opened and confirmed to show the named state without a loading overlay. They supplement the previous matrices; the alpha.3 originals remain intact.

| Filename | Screen | State shown |
| --- | --- | --- |
| `hearth-ui-0.png` | Add a world | Blank paste field, Add disabled, file-drop hint and local-world creation controls |
| `hearth-ui-1.png` | Named invite result | Copied title, friend name, private-transfer guidance, Copy invite again and Save invite as a file |
| `hearth-ui-2.png` | Saved invite result | Saved confirmation and Open folder with invite action |
| `hearth-ui-3.png` | Native alert | Induced file-write failure, retry/copy guidance and Back |
| `hearth-ui-4.png` | Saved invite result | Successful retry with the same complete invite |
| `hearth-ui-5.png` | Native alert | Rejected invalid file, required full-code guidance and retained-input explanation |
| `hearth-ui-6.png` | Add a world | Returned to the existing screen with `existing draft` retained |

Both sets have a physical framebuffer of 1708 × 960. `large` uses GUI scale 2 with a logical viewport of 854 × 480; `small` uses GUI scale 4 with a logical viewport of 427 × 240. The shown locale is Russian. The native font, centered forms, control labels and alert wrapping remain readable and separated at both densities; no clipping or overlap was found in this batch. The displayed friend name **Друг с Windows** and `existing draft` are driver demonstration values, not evidence of a Windows user or a completed invitation.

## Alpha.4 transfer behavior and verification

The batch addresses the practical transfer problem of an approximately 9,300-character invite. Only the creator signs into GitHub; friends receive keys scoped to the world and need no account login or separate backend. Creating an invite still copies the complete code. The result screen adds an optional file route and explains that the file should be sent privately to the named friend. Paste remains available in Add a world.

`en_us.json` and `ru_ru.json` define **Copy invite again** / **Скопировать инвайт ещё раз**, **Save invite as a file** / **Сохранить инвайт файлом**, and **Open folder with invite** / **Открыть папку с инвайтом**. Saving adds a confirmation and changes the save action to the folder action. `InviteResultScreen` retains the same code and destination across a write failure: Back returns to that screen for another save or copy. The Add screen validates a dropped file before replacing its field; rejected input returns to the same screen and preserves the prior draft. Blank Add remains disabled. A nonblank draft enables Add, with full invite validation still performed when Add is used.

Source inspection of `WorldAccessScreen.java`, `SharedWorldsScreen.java`, `HearthClient.java`, `Invite.java` and `LocalSecrets.java` confirms these paths. `Invite.readFile` accepts one regular file, limits reading to 64 KiB, removes surrounding whitespace and an optional UTF-8 BOM, and decodes the complete invite before returning it. Export uses the existing protected UTF-8 writer under the game directory's `hearth/invites/`. Invite-creation failure now returns to the same friend-name form; that path is source-confirmed and has no new live failure capture in this batch.

Log root: `/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/product-alpha4/`. Both `ui-confirm-large.log` and `ui-confirm-small.log` reach `NATIVE_INVITE_FILE_EXPORT_IMPORT_AND_RECOVERY_VERIFIED` and `[ui-test] All native screens checked`. `UiReviewDriver.transferScreen` directly invokes the native file-drop handler, verifies the full imported code and enabled Add, presses Copy and verifies exact clipboard equality, saves and reads back the complete exported invite, and verifies POSIX permissions `0600`. It induces a write failure, returns and retries with the same code, then rejects an invalid file and verifies return to the existing draft. The driver also checks title centering, control bounds, overlap and translated labels. This establishes the native handler and recovery paths; a physical OS drag was not performed.

Local JUnit results contain 15 tests with zero failures or errors, and `test`, `build` and `gametestClasses` completed successfully. `InviteTest` covers normal invite files, BOM plus CRLF, invalid and oversized content, missing files and directories. CI run [37514976545](https://github.com/scroogem/hearth/actions/runs/37514976545) completed successfully for `f5aa7de` on `macos-latest` and `windows-latest`, with 15 tests and a successful build on each. The recorded result is `/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/product-alpha4/ci-result.json`. These are build and core-test results; they do not establish Windows native UI or gameplay behavior.

## Alpha.4 finish status and limits

The fresh finish reviewer returned **ship**, scoped to the alpha.4 invite-transfer refinement at `f5aa7de`, all 14 captured native screens and the recorded transfer/recovery interactions. No material fixes or polish fixes were required in this scope. The five-section verdict is saved in [finish-review.md](/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/product-alpha4/finish-review.md). CI was pending when that verdict was written and subsequently passed as recorded above. The incumbent Minecraft system is preserved; no new visual system or detector applies to this ordinary refinement.

The folder action calls native `Util.OS.openPath` in source but was not clicked. Physical OS drag, Add completion, Play, OAuth completion, English rendering and exhaustive keyboard/narrator interaction remain outside this batch. The Mac ↔ Windows bundled Steam two-account test and a production Steam App ID remain release gates. No current Steam test, real Windows runtime result or public-release readiness is claimed. Alpha.3 gameplay evidence below is historical; permission enforcement was unchanged by alpha.4.

## Previous alpha.3 capture matrix and scaling

Historical native originals from 2026-10-05: `/Users/maks/Documents/hearth/.impeccable/review/alpha3/`.

Each filename below exists in both `large/` and `small/`, giving the required 32 captures. Every original was opened and confirmed to show its named screen and state.

| Filename | Screen | State shown |
| --- | --- | --- |
| `hearth-ui-0.png` | Shared worlds | Idle world, Play and Details |
| `hearth-ui-1.png` | Creator details | Creator role, Invite, settings, members and history |
| `hearth-ui-2.png` | Creator settings | Loaded settings, operator commands, Save and Cancel |
| `hearth-ui-3.png` | Creator game rules | Search for `keep_inventory`, translated name and enabled boolean changed to Off |
| `hearth-ui-4.png` | Members and permissions | Named member, participant role and Permissions action |
| `hearth-ui-5.png` | Member permissions | Editable name, Operator: Off, explanatory text, Ban, Save and Cancel |
| `hearth-ui-6.png` | Named invite | Empty friend-name field; Create and copy invite disabled |
| `hearth-ui-7.png` | Members and permissions | Member list shown again after returning |
| `hearth-ui-8.png` | IP bans | Empty ban list, address field and direct-connection explanation |
| `hearth-ui-9.png` | History and saves | Loaded sessions, save details and pagination |
| `hearth-ui-10.png` | Save details | Session activity and creator Restore action |
| `hearth-ui-11.png` | Participant details | Participant role, history and World rules; creator actions absent |
| `hearth-ui-12.png` | Participant world rules | Disabled setting controls, accessible Game rules, Back and no Save |
| `hearth-ui-13.png` | Participant game rules | Search for `keep_inventory`, translated name and disabled On boolean |
| `hearth-ui-14.png` | Add a world | Empty invite field with Add disabled; eligible local world and creation actions |
| `hearth-ui-15.png` | Create a shared world | Signed-in creator account and repository-access explanation |

The framebuffer is 1708 × 960 in both sets. `large` uses GUI scale 2, with a logical viewport of 854 × 480; `small` uses GUI scale 4, with a logical viewport of 427 × 240. These are two native GUI densities at one window size. Controls and text scale through Minecraft. The small logical viewport displays fewer history rows and an enabled Next control.

Historical log root: `/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/product-alpha3/`. Both `ui-confirm-large.log` and `ui-confirm-small.log` reach `[ui-test] All native screens checked` after all 16 screens. `src/gametest/java/hearth/mc/UiReviewDriver.java` checks title centering, Button/EditBox bounds, control overlap and translated control labels before each capture. The logs also record `RULE_SEARCH_AND_BOOLEAN_PERMISSION_VERIFIED readOnly=false` and `readOnly=true`: search retains its native input across rebuilds, the creator boolean changes, and the participant boolean is disabled. Visual inspection confirms readable, separated content in these viewports and the shown Russian locale.

### Alpha.3 roles and interaction labels

The alpha.3 source implements three roles in the creator-signed policy. Its captures establish the creator and participant presentation; command enforcement has separate historical gameplay evidence below. Alpha.4 preserves this enforcement.

| Role | Behavior |
| --- | --- |
| Creator | Manages world settings, members and permissions, invites, the approved mod set and save restoration. |
| Signed operator | Receives game-master command permission, equivalent to level 2, when operator commands are enabled. Access and restore remain creator actions; mode and creative restrictions still apply. |
| Participant | Can play and host without receiving automatic operator authority from hosting. Can inspect world rules and search game rules with editing disabled. Any permitted self-mode change follows creator rules. |

`en_us.json` and `ru_ru.json` provide the interface labels. Shared worlds uses **Play** / **Играть** and **Details** / **Подробнее**; live status decides whether Play hosts or joins. Details states the role. Creator settings and access are distinct from participant **World rules** / **Правила мира**. Member **Permissions** / **Права** opens the name and Operator On/Off controls. Named invites ask for a friend's name before **Create and copy invite**. Settings and boolean game rules use native localized On/Off labels. Search matches rule IDs and translated names, and truncated rule names have native tooltips. Previous/Next are disabled when unavailable; Back, Done and Cancel return to the appropriate prior screen.

Source inspection confirms native `ConfirmScreen` prompts for member Ban/Unban, approving the current mod set, and creator Restore. They identify the affected member, mod requirements or selected save. Restore asks the creator to stop the session and explains the new history entry and retained bans and rules. The Restore button is captured; these confirmation dialogs and their outcomes are outside the alpha.3 screenshot matrix.

### Alpha.3 empty, loading and retry behavior

Alpha.3 captures show a blank invite field with Add disabled and a blank friend-name field with Create and copy invite disabled. IP bans shows an empty list with native address entry. Source disables member Save for a blank name and retains typed field values across rebuilds. It also supplies shared-world and member empty messages with a next step, **No saved sessions yet**, and **No matching game rules**. These additional empty states are source-verified; no new captures or tests are claimed for them.

History and members distinguish loading from failure and offer Retry. Member Retry resets the failed load and policy before reloading. Play is disabled until a status is available; member Permissions is disabled until its policy is available. Creator Save is disabled until settings load and validate. Invalid numeric rules turn red and prevent saving, with a native tooltip explaining the correction. Invalid invite copy explains the required prefix and retained input. Settings, IP input and GitHub errors use native alerts. Current source supports these behaviors; the older member-error presentation evidence is recorded under alpha.2 below, and recovery after service restoration is unproven here.

### Alpha.3 native UI persistence evidence

`ui-member-edit.log` records `NATIVE_MEMBER_RENAME_AND_OPERATOR_SAVE_VERIFIED`. The native UI driver opens member Permissions, changes the name, enables Operator and presses Save. It reloads the signed world policy from the real GitHub-backed world and verifies the new name and operator flag, then publishes the original member values again. This establishes persistence through that actual UI path; the static member-edit screenshots alone do not establish a save.

### Separate historical alpha.3 gameplay evidence

These alpha.3 checks use `src/gametest/java/hearth/mc/RoleReviewDriver.java` and the permission source. They supplement the historical UI evidence at the tested connection and role scope; they were not rerun for alpha.4.

| Log under `product-alpha3/` | Verified scope |
| --- | --- |
| `owner-live.log` | `SERVER_ROLE_AND_COMMANDS_VERIFIED` for regular, operator, demoted and selfmode phases, plus a connected and stable guest. |
| `guest-live.log` | Peer join and `COMMAND_SUGGESTIONS_AND_PARTICIPANT_PAUSE_MENU_VERIFIED` for the same four phases. |
| `guest-host-alpha3.log` | Host ready and both role/command and command-suggestion/pause-menu markers for `physicalhost`, with the creator offline. |

The driver checks actual direct and wrapped commands, creator rule protection, creative restrictions, permitted changes to one's own mode and prevention of changing another player's mode. It also checks the received command tree and native participant pause navigation. The physical-host case verifies that hosting by a participant does not confer creator or operator powers. These are gameplay and interaction checks, separate from the 32-screen visual matrix.

## Previous alpha.2 evidence

The 2026-10-01 review used `/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/managed-peer-v2/`. Its `ui-confirm-large/screenshots/` and `ui-confirm-small/screenshots/` each contained the following seven inspected screens at GUI scales 2 and 4. This remains historical alpha.2 proof, with its original screen numbering.

| Filename | Previous screen and state |
| --- | --- |
| `hearth-ui-0.png` | Shared worlds: one idle world, Play and Details |
| `hearth-ui-1.png` | World details: creator actions and local-network help |
| `hearth-ui-2.png` | World settings: loaded settings, Save and Cancel |
| `hearth-ui-3.png` | World access: one member, Ban, IP bans and Back |
| `hearth-ui-4.png` | History and saves: saved sessions, save details and pagination |
| `hearth-ui-5.png` | Add a world: invite field and no eligible local worlds |
| `hearth-ui-6.png` | Create a shared world: creator explanation and GitHub sign-in action |

Both previous confirmation logs finished `[ui-test] All native screens checked`. The additional `ui-member-error/screenshots/hearth-ui-0.png` showed **Не удалось загрузить участников** and **Повторить**, with IP bans and Back separated, at GUI scale 4. Its log passed the native layout checks. That capture proved the error message and Retry presentation, without demonstrating a successful retry.

The alpha.2 finish reviewer scored title alignment, the history label, the empty selector and the additional member-load error fix resolved. Its disposition was **ship**, scoped to those four fixes in alpha.2.

## Previous alpha.3 finish status and limits

The fresh finish reviewer returned **ship**, scoped to the alpha.3 role and UX batch represented by commit `0de3306`, all 32 captured native screens at the two stated GUI densities, and the recorded permission interactions. No material defects were found in that batch. English rendering and exhaustive keyboard/narrator interaction remain outside the evidence. The two-account Mac ↔ Windows test using bundled Steam and a production Steam App ID remain release gates; App ID 480 is a development setting. The five-section verdict is saved in [finish-review.md](/Users/maks/Documents/Codex/2026-09-27/hearth-serverless/work/product-alpha3/finish-review.md).

That visual review does not prove OAuth completion, Steam connectivity, Windows compatibility or public-release readiness. The signed-in account screenshot establishes the shown account state. The separate historical gameplay logs cover the named local test scenarios. Confirmation outcomes, uncaptured error/empty states, other locales and other device configurations remain outside that visual evidence. Pre-existing drift and untested states are not promoted into new design rules or repaired by this documentation pass.
