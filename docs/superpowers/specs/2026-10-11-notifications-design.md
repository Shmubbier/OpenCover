# OpenCover: Notifications (sub-project 2b)

Date: 2026-10-11

## Goal

Read, act on and reply to notifications entirely on the cover screen, so the main screen is never needed.

## Features

- **Notifications page** (pager page 0): one card per conversation, newest first, with latest message and count. Tap to expand. Each notification shows its actions, swipes away to dismiss, and opens on the cover when tapped.
- **Banner**: slides down over any app on the cover for new alerting notifications. Sender, text, up to 3 actions. Hides after 5 s. Tap opens on the cover; swipe up hides. A burst shows the newest with "+N more".
- **Reply sheet**: message, text box (installed keyboard), mic for voice, Send. Opened from the banner or the page.
- Settings switch to turn pop-ups off. No banners while the phone is locked.

## Components

| Part | Role |
|---|---|
| NotificationAccess | Existing listener; feeds posted and removed notifications to the store |
| NotificationStore | In-memory list of current notifications as plain records |
| groupConversations() | Pure. Chats grouped by conversation id or title, others by app |
| NotificationsPage | The page above |
| Banner | Overlay window on display 1 (Compose) |
| ReplyActivity | Reply sheet on display 1; sends through the notification's RemoteInput action |

Notification records: key, app, profile, title, text, time, conversation key, actions (title, accepts reply).

## Rules

- Skipped: ongoing, media style (left for 2c), group summaries, OpenCover's own.
- Banner only for new, alerting notifications; updates replace in place without a banner.
- Opening a notification sends its content intent to display 1, never the main screen. If it can't open there, show a short message.
- Banking apps open through banking mode's pause.
- Reply failure keeps the typed text and shows "Couldn't send".
- No microphone permission or speech service: the mic button is hidden.
- Removed elsewhere: removed from page and banner.

## Verify first on device

1. Banner overlay shows and takes touches on display 1.
2. A notification's content intent opens its app on display 1.
3. Speech recognition is available (adds a microphone row to the setup checklist).

## Testing

- Unit: grouping, ordering, update replaces, skip rules, banner rules, burst count.
- Device: the three checks above, then Messenger thread grouping, actions, dismiss, open on cover, banner over another app, text and voice reply.

## Setup

Turn off Samsung's own cover-screen notification pop-ups to avoid doubles.

## Out of scope

Media (2c), T9 (sub-project 4), lock-screen pop-ups, per-app pop-up settings.
