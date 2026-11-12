---
app_name: Station 1
file_stem: Station_1
station: Station 1 goods receiving
mark: "1"
accent: "#1B4DA0"
accent_tint: "#9FBBE6"
icon: images/icon.png
version: 1.5.2
date: 2026-11-12
device: Chainway C72
standfirst: >-
  How to assign RFID tags to pallets, offload pallets against their purchase
  order or stock transfer, close the document, and what to do when Station 1
  refuses a scan.
description: Standard operating procedure for the PPNAM Station 1 goods-receiving handheld app 1.5.2 on the Chainway C72.
crop: {top: 72, bottom: 126}
revisions:
  - version: Rev. B
    date: 2026-08-31
    change: "Station 1 Scanner SOP (PPNAM-SOP-001, app v1.0), kept in the desktop repo as `docs/Scanner_SOP.html`."
  - version: 1.5.0
    date: 2026-11-06
    change: "Suite release: visual refresh, consistency pass, scan sounds."
  - version: 1.5.1
    date: 2026-11-12
    change: "Offload: one dialog after every pallet with a large pallet count. Next Pallet until the count is reached, then Complete. No Done step and no Cancel. Short Tags and Over Tags renamed Too Little Tags and Too Many Tags."
  - version: 1.5.2
    date: 2026-11-12
    change: "Offload: the dialog shows only the choices that are valid at the pallet count (Next Pallet and Too Many Tags until the count is reached, then Complete and Too Little Tags). A recorded Too Little Tags or Too Many Tags report returns to the home screen. Batch Reference is read-only."
---

<!-- Screenshots: v1.5.0 (8) debug build of master on the c72_clone emulator, against
     tools/station_sim.py on a private Mosquitto. For the captures the simulator's
     refusal wording was set to the Station 1 desktop's own text. Operators, tags, barcodes,
     PO-000123 and PO-000200 are simulator demo data.
     1.5.1 (9): 26, 27, 28, 30 and 40 were recaptured for the new Offload dialog; the other
     screens are unchanged since 1.5.0.
     1.5.2 (10): 24, 26, 27, 30 and 40 were recaptured and 32 added (valid-choices dialog,
     reported-and-home, read-only batch). -->

# Purpose and scope {#purpose}

The Station 1 app runs on the Chainway C72 handheld in goods receiving. Receiving operators use it for two jobs: **Tag Assignment**, which gives each prepared pallet its RFID tag, and **Offload**, which records each pallet coming off the truck against its purchase order or stock transfer and closes the document when the delivery is done. The Station 1 PC checks every scan and every sign-in.

## What it does

- Sends each scanned RFID tag to Station 1, which assigns it to the next prepared pallet and says whether it accepted the tag.
- Checks that a pallet's RFID tag and barcode belong together, then shows the pallet's expected bag weight, bag count and batch reference for you to check. You can correct the weight and the bag count.
- Shows which document the pallet belongs to and how many of its pallets are in (for example *PO-000123 · 5 of 12 pallets*).
- Closes the document as **Complete**, or reports **Too Little Tags** or **Too Many Tags** to Station 1, when you are done.

## What it does not do

- It does not prepare pallets, print labels or post to SAP. Those happen on the Station 1 desktop.
- It does not choose or lock a document. The document comes from each pallet's tag and barcode.
- It cannot undo an offload or reopen a closed document. Corrections are made on the Station 1 desktop.
- It keeps nothing to send later. Every scan needs Station 1 online.

> [!NOTE] Who this is for
> Receiving operators at Station 1. Sections marked **Supervisor** are for the shift supervisor, who holds the settings PIN.

# Before you start {#before}

- [ ] The handheld is charged. Put it back on its charger at the end of the shift.
- [ ] You are on the plant Wi-Fi, in coverage at the receiving bay.
- [ ] You know your Station 1 username and password, or you have your RFID badge.
- [ ] The Station 1 PC is running. The status pill shows **Connected** (section 3).
- [ ] For Tag Assignment: the pallets are prepared on the Station 1 desktop. For Offload: each pallet has its RFID tag and printed barcode label.

::: step Opening the app
shot: 01_login_empty.png
caption: The Log In screen, ready for a username or a badge.
alt: Station 1 Log In screen with Username and Password fields, a blue Log In button, "or scan your badge" and a Connected pill
The handheld starts in the **PPNAM Station Launcher**. Tap the blue **Station 1** tile.

The app opens on **Log In**, or straight on the home screen if you are still signed in. Wait a few seconds for the status pill in the top-right corner to settle on **Connected** before you sign in.
:::

# The status pill and header {#status}

The coloured pill in the top-right corner is on every screen. It reports two links: the **network link** (the broker that carries messages) and **Station 1 itself** (the PC at the station). It waits 1.5 seconds before it changes, so a short blip does not make it flicker.

::: pills
go | Connected | The network is up and Station 1 is running. Everything works. | Carry on.
warn | Reconnecting | The handheld lost the network link and is getting it back. It retries every 5 seconds. | Wait. Scans fail with *Failed to send — check connection* until it clears. If it does not clear, move into Wi-Fi coverage.
warn | Station 1 offline | The network is fine, but the Station 1 PC is not running or not reachable. You are signed out, and sign-in is blocked. | Wait for **Connected**, then sign in again. Tell your supervisor if it lasts.
stop | Offline | The handheld is not trying to connect: it has no broker account set up yet, or a supervisor is applying new settings. | Ask your supervisor to set up the broker in Settings.
:::

::: shots
12_home_reconnecting.png | Reconnecting: the link dropped and is coming back. | Home screen with an amber Reconnecting pill
08_login_station_offline.png | Station 1 offline: you are signed out until the station is back. | Log In screen with an amber Station 1 offline pill and a red banner "Station is offline. Sign-in is unavailable until the station app is running."
02_login_offline.png | Offline: no broker account set up on this handheld. | Log In screen with a red Offline pill
:::

::: step The header
shot: 10_home.png
caption: Home: your name and role, Settings, the pill, and the two jobs.
alt: Station 1 home screen with "Bongi Both · Operator", the Settings gear, a Connected pill and blue Tag Assignment and teal Offload tiles
- **Your name and role** sit under the logo. Tap them to log out (section 10).
- The **gear** opens Settings.
- On Tag Assignment and Offload the header has a **back arrow** and the screen name instead.

The home screen has two tiles: **Tag Assignment** and **Offload**. Scans do nothing on the home screen.
:::

::: step Tiles you cannot use
shot: 11_home_tag_only.png
caption: This operator may only assign tags, so Offload is dimmed.
alt: Home screen for "Thandi Tag · Operator" with the Offload tile dimmed
Station 1 decides which jobs each operator may do. A tile you are not allowed to use is dimmed and does nothing when tapped. If you need it, ask your supervisor to change your account on the Station 1 desktop.
:::

# Signing in {#signin}

Everything you scan is recorded against your name, so the app does nothing until you sign in. You can sign in with your username and password, or with your badge.

::: step By username and password
shot: 03_login_dropdown.png
caption: Tap the arrow to pick your username from the station's list.
alt: Username dropdown open, listing "op.both — Bongi Both", "op.none — Nomsa None", "op.off — Owen Offload" and "op.tag — Thandi Tag"
1. Tap **Username**. Pick your name from the list (each row reads *username — Your Name*) or type your username.
2. Tap **Password** and type your password. The eye icon shows what you typed.
3. Tap **Log In**, or press Enter on the keyboard.

The list comes from Station 1 and is kept on the handheld, so it is there even while the station is offline. A username that is not in the list still works.
:::

::: step While Station 1 checks
shot: 04_login_logging_in.png
caption: The spinner shows while Station 1 checks your password.
alt: Log In screen with username op.both, a masked password and a spinner on the Log In button
A spinner replaces the button label and both fields are locked until Station 1 answers, for up to 10 seconds. Your password itself is never sent over the network.
:::

::: step By badge
shot: 06_login_badge_rejected.png
caption: A badge Station 1 does not know is refused.
alt: Log In screen with the red message "Badge not recognised. Log in with your username and password."
While the Log In screen is showing, **scan your RFID badge**. You do not need to tap anything first.

A second badge read while a sign-in is already running is ignored, so a double read cannot sign in the wrong person. An unknown badge gives *Badge not recognised. Log in with your username and password.* Signing in makes no scan sound.
:::

::: step If sign-in fails
shot: 05_login_error.png
caption: The reason appears in red above the fields.
alt: Log In screen with the red message "Incorrect username or password."
The reason appears in red at the top of the card. Typing in either box hides it again. The common ones:

- **Incorrect username or password.** Retype them carefully.
- **Station 1 did not respond. Check the station and try again.** No answer within 10 seconds. Check the pill, then tap **Log In** again.
- **Station is offline. Sign-in is unavailable until the station app is running.** Wait for **Connected**.
- **Please fill in all fields.** Both boxes are needed.

Every sign-in message is listed in section 8.
:::

## Signed out by the app

::: step Why you were signed out
shot: 07_login_session_ended.png
caption: Why you were signed out is shown on Log In.
alt: Log In screen with the red message "Your session ended. Sign in again."
- If nobody touches the handheld or scans for **15 minutes**, the app signs you out with *Signed out after 15 minutes of inactivity.* Your supervisor can change the time. Time with the app in the background counts too.
- If Station 1 goes offline while you are signed in, you are signed out with *Station went offline — you were signed out. Sign in again when the station is back online.*
- If Station 1 ends your session during a scan, you see *Your session ended. Sign in again.*

Sign in again and repeat the last scan.
:::

# Tag Assignment {#workflow-tags}

There is nothing to type: scanning is the whole job. Each tag you scan is sent to Station 1 straight away, and Station 1 assigns it to the next pallet prepared on the desktop.

::: step Scan a tag
label: Step 1
shot: 15_tag_idle.png
caption: Ready. The tag goes to Station 1 as soon as you scan it.
alt: Tag Assignment screen with a tag icon, "Scan Tag", "Tag will be sent automatically after scan." and LAST SCANNED "No tag scanned yet"
On the home screen, tap **Tag Assignment**. Hold the handheld to the pallet's RFID tag and pull the trigger. There is no button to press.
:::

::: step Wait for the answer
label: Step 2
shot: 16_tag_sending.png
caption: Sending… until Station 1 answers.
alt: LAST SCANNED E280689400005015ABCD5678 with a spinner and "Sending…"
**Last Scanned** shows the tag you read, with *Sending…* underneath until Station 1 answers. It answers within a second or two; the app waits up to 10 seconds.
:::

::: step Assigned
label: Step 3
shot: 17_tag_assigned.png
caption: Green: the tag is assigned. Scan the next one.
alt: LAST SCANNED E280689400005015ABCD1234 with the green status "Tag assigned."
A green *Tag assigned.* means Station 1 accepted the tag. You hear the success chirp. Scan the next tag.

Scanning a tag you have already assigned again is harmless: Station 1 confirms it again.
:::

::: step If a tag is refused
shot: 18_tag_rejected.png
caption: A refused tag, with Station 1's reason.
alt: LAST SCANNED TAG-USED-001 with a red panel "This RFID tag is already assigned to another pallet."
A red panel shows Station 1's reason, and you hear the fail buzz. The tag is **not** assigned. The usual ones:

- *This RFID tag is already assigned to another pallet.* Use a different tag.
- *No prepared pallet row is waiting for an RFID tag in the active desktop session.* Every prepared pallet already has a tag. Prepare more on the desktop first.
- *This RFID tag is disabled, lost, retired, or otherwise unavailable.* Set the tag aside and tell your supervisor.

Section 8 lists every message.
:::

::: step If Station 1 does not answer
shot: 19_tag_timeout.png
caption: No answer in 10 seconds. Retry sends the same tag again.
alt: Red panel "Station 1 did not respond. Check the station and retry." with a Retry button
*Station 1 did not respond. Check the station and retry.* or *Failed to send — check connection* comes with a **Retry** button. Check the pill, then tap **Retry**: it sends the last tag again. Scanning the tag again does the same.
:::

::: step A badge instead of a tag
shot: 20_tag_badge_refused.png
caption: Badges are never sent as pallet tags.
alt: Tag Assignment with a pop-up message "That is a user badge, not a pallet tag"
If you scan a staff badge, the app refuses it on the handheld with *That is a user badge, not a pallet tag* and the fail buzz. Nothing is sent.
:::

# Offload {#workflow-offload}

Use Offload as each pallet comes off the truck. You scan the pallet's RFID tag and its barcode label, Station 1 checks that they belong to the same pallet and shows its expected values, you confirm them, and when the delivery is finished you close the document.

::: step Scan the tag and the barcode
label: Step 1
shot: 22_offload_scanned.png
caption: Both values in. Match Pallet is now blue.
alt: Offload SCAN card with TAG-PAL-001 in the RFID field, BC-002 in the barcode field and a blue Match Pallet button
On the home screen, tap **Offload**. Scan the pallet's **RFID tag** and its **barcode label**, in either order. Each scan fills its own box; you can also type them.

**Match Pallet** stays grey until both boxes are filled. A scan only fills a box: it does not send anything.
:::

::: step Match Pallet
label: Step 2
shot: 23_offload_mismatch.png
caption: A refused pair. Rescan the pallet; the red panel clears on the new scan.
alt: Red panel "RFID tag and barcode belong to different pallets." under the scanned tag and barcode
Tap **Match Pallet**. *Sending…* shows while Station 1 checks the pair.

If Station 1 refuses the pair you hear the fail buzz and its reason appears in a red panel, for example *RFID tag and barcode belong to different pallets.* Scan the tag and the label **on the same pallet** again. The panel clears as soon as you scan a different value. Section 8 lists every reason.
:::

::: step Check the pallet values
label: Step 3
shot: 24_offload_matched.png
caption: Matched. The values come from Station 1; correct the weight or bag count if the pallet differs.
alt: PALLET VALUES card "PO-000123 · 5 of 12 pallets" with Bag Weight (kg) 25, Number of Bags 40, Batch Reference BATCH-A, Confirm Offload and Back to Scan
On a match you hear the success chirp and the **Pallet Values** card opens. Its heading shows the document and its progress, for example *PO-000123 · 5 of 12 pallets*.

1. Check **Bag Weight (kg)** and **Number of Bags** against the pallet. Correct either if it differs.
2. Check **Batch Reference** against the label. You cannot change it on the handheld: if it is wrong, tap **Back to Scan**, set the pallet aside and tell your supervisor.
3. Tap **Confirm Offload**, or press Done on the keyboard in **Number of Bags**.

**Back to Scan** leaves this pallet without recording it and keeps the scanned tag and barcode. Scans are ignored while this card is open.
:::

::: step If a value is refused
shot: 25_offload_validation.png
caption: Fix the value; the message clears as you type.
alt: Bag Weight 0 and a red panel "Enter a bag weight greater than 0."
The app refuses an impossible value before sending it: *Enter a bag weight greater than 0.* or *Enter a whole number of bags greater than 0.* Station 1 can also refuse a value, for example *Bag count is too large for the confirmed bag weight.* Either way you stay on the card: fix the value and tap **Confirm Offload** again.

If Station 1 does not answer, the panel shows a **Retry** button. Your values are kept.
:::

::: step Pallet recorded
label: Step 4
shot: 26_offload_recorded_dialog.png
caption: The large number is this document's pallet count. Next Pallet carries on.
alt: Pallet recorded dialog with a large "6 of 12" above "pallets offloaded against PO-000123" and the buttons Next Pallet (blue) and Too Many Tags (red)
Station 1 records the pallet, you hear the success chirp, and the boxes clear. A dialog shows the new count in large figures, for example **6 of 12**, with *pallets offloaded against PO-000123* under it.

While pallets are still expected the dialog is headed *Pallet recorded*:

- **Next Pallet**: carry on with the next pallet on the truck. This is the everyday choice.
- **Too Many Tags**: only when the truck is empty and prepared pallets did not arrive, so tags are left over. See step 5.

**Complete** and **Too Little Tags** are not offered yet: Station 1 only takes them once every expected pallet is in.

There is no Cancel, and the dialog does not close on Back or a tap outside it: choose one.
:::

## Closing the document

::: step When the count is reached
label: Step 5
shot: 27_offload_close_sheet.png
caption: Every expected pallet is in. The choices are now Too Little Tags and Complete.
alt: Close PO-000200 as… dialog with a large "2 of 2" above "pallets offloaded against PO-000200" and the buttons Too Little Tags (amber) and Complete (green)
The dialog only shows the choices that are possible at the current count.

When the last expected pallet is recorded, for example **2 of 2**, the dialog is headed *Close PO-000200 as…*. Choose what is physically true:

- **Complete**: every pallet that arrived has been offloaded, and the tags matched the pallets. The document closes straight away.
- **Too Little Tags**: there were **too few tags**: pallets arrived that have no tag yet.

Before the count is reached, for example when the truck is empty at 10 of 12, the one closing choice is:

- **Too Many Tags**: there are **tags left over**: fewer pallets arrived than were prepared.
:::

::: step Say how many tags
shot: 28_offload_tag_count.png
caption: Too Little Tags and Too Many Tags ask for the number of tags.
alt: Dialog "How many tags too little?" with a Number of tags field, Cancel and Submit, and the number keyboard open
For **Too Little Tags** the app asks *How many tags too little?* (the pallets without a tag); for **Too Many Tags**, *How many tags too many?* (the unused tags). Type the number and tap **Submit**.

The number must be a whole number, 1 or more; otherwise the dialog stays open with *Enter a whole number, 1 or more.* **Cancel** takes you back to the choices.
:::

::: step After you close
shot: 31_offload_closed.png
caption: Closed. The app returns to the home screen.
alt: Home screen with the message "PO-000123 closed — Complete"
When Station 1 closes the document, the app returns to the home screen with *PO-000123 closed — Complete* (for Too Little Tags or Too Many Tags it adds the number, for example *(3 tags)*). Closing makes no scan sound.
:::

::: step After a Too Little Tags or Too Many Tags report
shot: 32_offload_reported.png
caption: Reported. The app returns to the home screen; the receipt stays open for the desktop.
alt: Home screen with the message "PO-000200 — Too Little Tags reported (3 tags)"
Station 1 records a Too Little Tags or Too Many Tags report and keeps the receipt open until a supervisor resolves it on the desktop. Your part is done: the app returns to the home screen with, for example, *PO-000200 — Too Little Tags reported (3 tags)*.

Tell your supervisor. After the desktop work, open **Offload** again, offload any pallets that were added, and close as **Complete**.
:::

::: step When Station 1 refuses to close
shot: 30_offload_close_refused.png
caption: Station 1 refused the close. Its reason is at the top, with Back to Scan.
alt: Close PO-000200 as… dialog with Station 1's reason above the count 2 of 2, the choices Too Little Tags and Complete, and Back to Scan
Station 1 may refuse a close, or not answer. The dialog opens again with the reason at the top, and the same reason stays on the Offload screen.

Read the reason. Choose again if you picked the wrong one, or leave the dialog: tap **Back to Scan**, which appears when the count is already reached, or **Next Pallet** when pallets are still expected. If the reason is not something you can fix, tell your supervisor.
:::

> [!WARNING] Closing is final
> Pallets of a closed document can no longer be offloaded. Only choose **Complete**, **Too Little Tags** or **Too Many Tags** when the truck is empty for that document.

# Scan sounds and vibration {#sounds}

Every PPNAM handheld uses the same two sounds. The Chainway scanner beeps by itself when it reads anything; the app's sound comes **after** that, when the answer is known.

- **Success chirp** (a bright rising two-note chirp) with one short 40 ms vibration: the scan was **accepted**.
- **Fail buzz** (a low falling double buzz) with two 200 ms vibrations: the scan **failed**. Read the screen.

The sounds play at the **media volume**. If you cannot hear them, turn the media volume up; the vibration still tells you.

Table: What you hear in the Station 1 app
| When | Outcome | Sound |
|---|---|---|
| Tag Assignment | Tag assigned (or confirmed again) | {ok} |
| Tag Assignment | Tag refused, no answer, or not sent | {fail} |
| Tag Assignment, Offload scan step | A staff badge read instead of a pallet tag | {fail} |
| Offload, Match Pallet | Tag and barcode matched | {ok} |
| Offload, Match Pallet | Pair refused, no answer, or not sent | {fail} |
| Offload, Confirm Offload | Pallet recorded | {ok} |
| Offload, Confirm Offload | Refused, no answer, or not sent | {fail} |
| Offload, while Station 1 is checking a pair, a pallet or a close | A scan of a different value (refused, not used) | {fail} |
| Offload | A scan fills the tag or barcode box; any scan on the Pallet Values card (ignored) | {none} |
| Everywhere else | Signing in, tapping, value checks on the handheld, closing a document | {none} |

## The repeat-read rule

One pull of the trigger can read the same tag several times. A repeat of a value that was **just refused on the handheld**, within 1.5 seconds, makes no sound, so holding the trigger never buzzes twice. A different value plays its own sound. On Tag Assignment only the answer to the newest read sounds, so one trigger pull sounds once; on Offload, a repeat of the value being checked is ignored.

# Messages and what to do {#messages}

Every message the app shows, word for word. Station 1 writes its own refusal reasons; the ones it uses today are listed below by job. *Ask a supervisor* means the problem cannot be fixed on the handheld. If a red panel shows only a code in capitals (for example `INTERNAL_ERROR`), Station 1 sent no reason: tell your supervisor the code.

Table: Signing in
| Message | What it means | What to do |
|---|---|---|
| Please fill in all fields. | The username or password box is empty. | Fill in both. |
| Incorrect username or password. | Station 1 did not accept the password. | Retype carefully. Ask your supervisor if you are locked out. |
| Badge not recognised. Log in with your username and password. | The badge is not registered at Station 1. | Sign in with your username, and ask a supervisor to register the badge. |
| Login was refused by the station (*code*). Ask a supervisor. | Station 1 refused for another reason. | Tell your supervisor the code. |
| Not connected to the station. | The handheld has no network link. | Check the pill and Wi-Fi, then retry. |
| Station 1 did not respond. Check the station and try again. | No answer within 10 seconds. | Check that the Station 1 PC is running, then retry. |
| Login failed. Try again. | Sign-in failed for another reason. | Try again. Report it if it repeats. |
| Station is offline. Sign-in is unavailable until the station app is running. | The Station 1 PC is not running. | Wait for **Connected**, then sign in. |
| Station went offline — you were signed out. Sign in again when the station is back online. | Station 1 went offline while you were signed in. | Wait for **Connected**, then sign in again. |
| Your session ended. Sign in again. | Station 1 ended your session. | Sign in again and repeat the last scan. |
| Signed out after *N* minutes of inactivity. | The automatic sign-out ran. | Sign in again. |

Table: Tag Assignment
| Message | What it means | What to do |
|---|---|---|
| Sending… | Waiting for Station 1. | Wait. |
| Tag assigned. | Station 1 assigned the tag. | Scan the next tag. |
| That is a user badge, not a pallet tag | A staff badge was read. | Scan the pallet tag. |
| Station 1 did not respond. Check the station and retry. | No answer within 10 seconds. | Check the pill, then **Retry**. |
| Failed to send — check connection | The handheld has no network link. | Move into coverage, then **Retry**. |
| This RFID tag is already assigned to another pallet. | The tag belongs to another pallet. | Use a different tag. |
| This RFID tag is already marked as assigned. | The tag is already in use. | Use a different tag. |
| No prepared pallet row is waiting for an RFID tag in the active desktop session. | Every prepared pallet already has a tag. | Prepare more pallets on the desktop first. |
| No active desktop receiving session is available for tag assignment. | No delivery is open on the desktop. | Ask a supervisor to open the receiving session. |
| The active desktop receiving session is not open for tag assignment. | The open delivery is past tagging. | Ask a supervisor. |
| This RFID tag is disabled, lost, retired, or otherwise unavailable. | The tag must not be used. | Set it aside and tell your supervisor. |
| A valid RFID tag is required. | The read was damaged. | Scan the tag again. |
| This operator is not permitted to use the requested scanner workflow. | Your account may not assign tags. | Ask a supervisor. |

Table: Offload, scanning and matching
| Message | What it means | What to do |
|---|---|---|
| RFID tag and barcode belong to different pallets. | Tag and label are from two pallets. | Scan the tag and label on the same pallet. |
| RFID tag is not assigned to an eligible Station 1 pallet. | The tag is not on a pallet waiting to offload. | Check the tag. Ask a supervisor if it is the right pallet. |
| Barcode does not resolve to a printed Station 1 pallet. | The label is not a Station 1 pallet label. | Scan the pallet label, not another sticker. |
| This pallet has already been offloaded. | Recorded earlier. | Move on to the next pallet. |
| The pallet does not belong to an open Station 1 receiving document. | Its document is closed or not open. | Ask a supervisor. |
| The pallet has no valid bag weight prefill. / The pallet has no valid bag count prefill. / The pallet has no batch reference. | The pallet was prepared without that value. | Ask a supervisor to correct the pallet on the desktop. |
| A valid RFID tag is required. / A valid pallet barcode is required. | A value was empty or damaged. | Scan it again. |
| Station sent an incomplete match. Try again. | Station 1's answer was missing values. | Tap **Match Pallet** again. Report it if it repeats. |
| Station 1 did not respond. Check the station and retry. | No answer within 10 seconds. | Check the pill, then **Retry**. |
| Failed to send — check connection | No network link. | Move into coverage, then **Retry**. |

Table: Offload, pallet values
| Message | What it means | What to do |
|---|---|---|
| Enter a bag weight greater than 0. | Weight empty, zero or not a number. | Enter the weight in kg. |
| Enter a whole number of bags greater than 0. | Bag count empty, zero or not whole. | Enter the number of bags. |
| Bag weight must be a positive number. / Bag weight must be at least 0.001 kg. | Station 1 refused the weight. | Correct it and confirm again. |
| Bag count must be a positive whole number. / Bag count is too large for the confirmed bag weight. | Station 1 refused the bag count. | Correct it and confirm again. |
| Batch reference is required. | Station 1 got no batch reference. | **Back to Scan**, set the pallet aside and tell your supervisor. |
| Batch reference must match the printed label. Correct it in Prepare Delivery and reprint before offloading. | The batch differs from the label. | Set the pallet aside and tell your supervisor. |
| The pallet does not belong to the referenced receiving document. | The pallet changed document since the match. | Scan the pallet again. |
| The referenced receiving document is no longer open. | The document was closed meanwhile. | Ask a supervisor. |
| This pallet was already offloaded with different captured values or by another scanner operation. | Someone else recorded it. | Ask a supervisor. |
| Offload recorded | The pallet is recorded. | Tap **Next Pallet**, or close the document when the delivery is finished. |

Table: Closing the document
| Message | What it means | What to do |
|---|---|---|
| Enter a whole number, 1 or more. | The tag count is empty, zero or not whole. | Enter the number of tags. |
| *Document* closed — *Status* (*N* tags) | Station 1 closed the document. | Nothing. Start the next document. |
| *PO-000200* — Too Little Tags reported (*N* tags) | Too Little Tags was recorded and the receipt stays open; the desktop must prepare the extra pallets. | Tell your supervisor. Offload the new pallets, then close as **Complete**. |
| *PO-000200* — Too Many Tags reported (*N* tags) | Too Many Tags was recorded and the receipt stays open; the desktop must remove the unused pallets. | Tell your supervisor. |
| The reported tag count does not match the recorded discrepancy or remaining prepared pallets. / The tag count differs from the resolved scanner report. | The number of tags is wrong. | Count again and resubmit. |
| The *status* classification does not match the current pallet progress (*X* of *Y* included pallets). | The choice does not fit the progress. | Check what was received and choose again. |
| The receiving document was already closed with a different classification. | Someone closed it already. | Ask a supervisor. |
| No single open Station 1 receiving document matches this reference. | The document is not open. | Ask a supervisor. |
| Station 1 did not respond. Check the station and retry. | No answer within 10 seconds. | Check the pill and choose again. |

Table: Settings
| Message | What it means | What to do |
|---|---|---|
| Enter the 6-digit PIN | The PIN box is empty. | Supervisor only. |
| Incorrect PIN. *N* attempts left before lockout. | Wrong PIN. | Supervisor only. |
| Too many attempts. Try again in *N*s. | Five wrong PINs lock the box for 30 seconds. | Wait for the countdown. |
| Host required / Invalid port (1–65535) / Enter 0–1440 | A field is empty or out of range. | Supervisor: correct the field. |
| Enter the broker username and password | No broker account is saved yet. | Supervisor: enter both. |
| Could not store the password securely | The handheld could not save the password. | Retry; report it if it repeats. |
| Saved, but could not connect to *host*:*port*. Check the broker settings. | The new settings were saved but do not connect. | Supervisor: check the values. |

# Settings {#settings}

The gear opens Settings from the Log In and home screens. The top half is open to everyone and is what support will ask you to read out. The bottom half needs the supervisor PIN.

::: step Diagnostics: anyone
shot: 40_settings_diag.png
caption: Diagnostics, and the PIN gate below.
alt: Settings Diagnostics: MQTT Broker Connected, Station 1 Online, Version v1.5.2 (10), Device ID scanner_59fcb3c4129d, and "Enter supervisor PIN to edit settings"
- **MQTT Broker**: the network link on its own (Connected, Reconnecting, Offline).
- **Station 1**: the station PC (Online, Offline, Unknown). Broker *Connected* with Station 1 *Offline* means the network is fine and the station is the problem.
- **Version**: `v1.5.2 (10)` for this release.
- **Device ID**: this handheld's identity, such as `scanner_59fcb3c4129d`. Read it out when you report a fault.
- **Operator** at the bottom shows who is signed in, with **Log out**.
:::

> [!SUPERVISOR] Configuration
> Type the supervisor PIN and tap **Unlock**. Five wrong attempts lock the PIN box for 30 seconds, and the count survives leaving Settings. Never tell an operator the PIN; operators should ask the supervisor.

::: step Unlocked: supervisors only
shot: 41_settings_unlocked.png
caption: Unlocked, showing the standard broker settings.
alt: Unlocked Settings showing Port 443, Use WebSocket on, Use TLS on, Username admin, an empty Password field and Auto sign-out (minutes) 15
- **Host** and **Port**: normally `mqtt.sysone.co.za` and `443`.
- **Use WebSocket** and **Use TLS**: both on for the standard setup.
- **Username** and **Password**: the broker account, not an operator account. The saved password is never shown; leave the box blank to keep it.
- **Auto sign-out (minutes)**: 15 by default, 0 = never, up to 1440.

A new handheld has no broker password and stays **Offline** until one is saved here once.
:::

::: step Test & Apply
shot: 42_settings_applied.png
caption: Connected — settings saved.
alt: Settings with "Connected — settings saved" in green above the Test & Apply button
**Test & Apply** saves the settings, then reconnects with them while *Testing connection…* shows, for up to 10 seconds. The operator stays signed in.

- *Connected — settings saved*: done.
- *Saved, but could not connect to host:port. Check the broker settings.*: the values are saved but do not work. Correct them and tap **Test & Apply** again.
:::

# Finishing up {#finishing}

::: step Handing the handheld over
shot: 45_logout_dialog.png
caption: Log out asks first.
alt: Log out? dialog reading "You'll need to log in again to continue." with Cancel and a red Log out button
1. Finish the pallet in hand: confirm it or tap **Back to Scan**.
2. Tap your name in the header (or **Log out** in Settings).
3. Confirm **Log out**.

Always log out before you pass the handheld to someone else. Otherwise their scans are recorded under your name.
:::

::: step Closing the app
shot: 46_exit_dialog.png
caption: Back from Log In or the home screen.
alt: Close the app? dialog reading "You'll leave PPNAM Station 1 and return to the home screen." with Stay and Close
From the Log In or home screen, the **Back** key asks *Close the app?*. Tap **Close** to return to the Launcher, or **Stay**.

At the end of the shift, log out and put the handheld on its charger.
:::

# Troubleshooting {#troubleshooting}

Table: Common problems
| Problem | Likely cause | What to do |
|---|---|---|
| The pill shows amber **Station 1 offline** and you cannot sign in. | The Station 1 PC is off, or its program is not running. | Tell your supervisor. Sign in once the pill shows **Connected**. |
| The pill stays **Reconnecting**. | No Wi-Fi where you are. | Move into coverage. If it stays, your supervisor checks Settings. |
| The pill is red **Offline** on a new handheld. | No broker account has been saved. | Your supervisor sets it up in Settings. |
| A tile on the home screen is dimmed. | Your account may not do that job. | Ask your supervisor. |
| Every tag is refused with *No prepared pallet row is waiting…* | All prepared pallets already have tags. | Prepare more pallets on the desktop. |
| **Match Pallet** stays grey. | One of the two boxes is empty. | Scan both the tag and the barcode. |
| Scans do nothing. | The screen does not take scans (home, Pallet Values card), or the app is not in front. | Go to the screen that asks for a scan. |
| A close keeps coming back with a reason. | Station 1 cannot close the document as chosen. | **Back to Scan** (or **Next Pallet**) and tell your supervisor. |
| No scan sounds. | Media volume is down. | Turn up the media volume. The vibration still works. |
| Signed out unexpectedly. | 15 minutes without a touch or scan, Station 1 went offline, or Station 1 ended the session. | Read the message on Log In and sign in again. |

> [!NOTE] Reporting a fault
> Give your supervisor the **Device ID** and **Version** from Settings, the time, the tag or barcode, and the exact message on screen.
