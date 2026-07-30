# Smart Registration Status Fix

This plan fixes the "Ghost Online" issue where users restored to the server after a wipe appear as Online even if they are inactive.

## User Review Required

> [!IMPORTANT]
> - **Server Change**: I will modify the `/register` endpoint to accept an `isOnline` boolean.
> - **Accuracy**: Active users will be registered as `Online`, while secondary contacts (being restored from Master DB) will be registered as `Offline` until their own phones send a heartbeat.

## Proposed Changes

### Server Side (Node.js)

#### [MODIFY] [index.js](file:///home/dogukan/Desktop/kopya5/index.js)
- Update `/register` endpoint: If `isOnline` is false, set `lastSeen = 0`.

### Android Side (Kotlin)

#### [MODIFY] [UserRepository.kt](file:///home/dogukan/Desktop/kopya5/kopya5/app/src/main/java/com/dogu/livekit/network/UserRepository.kt)
- Update `auth(...)` to include `isOnline: Boolean`.
- Update `restoreCurrentUserOnServer` to send `isOnline = true`.
- Update `syncUnsyncedUsers` and `restoreMissingUsersOnServer` to send `isOnline = false`.

#### [MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya5/kopya5/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)
- Update all `auth` calls to pass the correct status (True for direct registration, False for background syncs).

## Verification Plan

### Manual Verification
1.  **Ghost Online Test**:
    - Wipe server.
    - Let the phone restore 3 accounts.
    - Check `/users` link immediately.
    - **Expected Result**: Only the current active user should be `isOnline: true`. The other 2 should be `isOnline: false`.
2.  **Server Logs**:
    - Ensure registration still works correctly for both scenarios.
