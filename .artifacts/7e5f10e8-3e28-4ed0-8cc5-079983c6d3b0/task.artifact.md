# Single-Stream Synchronization Tasks

- [ ] **Data Layer & Repository**
    - [ ] Implement `performUnifiedSync` in `UserRepository.kt`
    - [ ] Remove `syncUnsyncedUsers`, `restoreCurrentUserOnServer`, `restoreMissingUsersOnServer`
- [ ] **Activity Logic**
    - [ ] Update `MainActivity.kt` to use `performUnifiedSync`
    - [ ] Remove redundant/conflicting sync helpers in `MainActivity.kt`
- [ ] **Verification**
    - [ ] Verify server logs show exactly one registration per user after restart
    - [ ] Verify `isOnline` status is stable
