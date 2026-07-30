# Implementation Plan - MVVM Refactoring

Refactor the LiveKit project to follow the MVVM (Model-View-ViewModel) architecture. This involves moving business logic and data handling out of Activities and into ViewModels and Repositories, using Dependency Injection (Hilt) for decoupling and StateFlow for state management.

## User Review Required

> [!IMPORTANT]
> This is a major architectural change that affects almost every part of the application.
> - **Dependency Injection**: I will introduce **Hilt** as the DI framework.
> - **State Management**: I will use **Kotlin StateFlow** for observing UI states in ViewModels.
> - **Refactoring Strategy**: Since `MainActivity.kt` is very large (~2000 lines), I will refactor it by extracting logic into specialized ViewModels (`AuthViewModel`, `ContactsViewModel`, `CallViewModel`, `HistoryViewModel`).

## Proposed Changes

### 1. Dependency Injection & Core Setup
- **[NEW] [LiveKitApplication.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/LiveKitApplication.kt)**: Application class with `@HiltAndroidApp`.
- **[NEW] [AppModule.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/di/AppModule.kt)**: Hilt module for providing `AppDatabase`, `SessionPreferences`, `NetworkClient`, and `Repositories`.
- **[MODIFY] [libs.versions.toml](file:///home/dogukan/Desktop/kopya6/kopya6/gradle/libs.versions.toml)**: Add Hilt and Lifecycle ViewModel dependencies.
- **[MODIFY] [build.gradle.kts](file:///home/dogukan/Desktop/kopya6/kopya6/build.gradle.kts)** & **[app/build.gradle.kts](file:///home/dogukan/Desktop/kopya6/kopya6/app/build.gradle.kts)**: Apply Hilt plugins and dependencies.
- **[MODIFY] [AndroidManifest.xml](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/AndroidManifest.xml)**: Register the new `LiveKitApplication`.

### 2. Data Layer Refactoring
- **[MODIFY] [UserRepository.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/network/UserRepository.kt)**: Convert from `object` to a class, inject dependencies.
- **[NEW] [CallRepository.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/data/repository/CallRepository.kt)**: Handle call log database operations.
- **[MODIFY] [SessionPreferences.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/pref/SessionPreferences.kt)**: Convert to class for Hilt injection.

### 3. ViewModel Layer (New Components)
- **[NEW] [AuthViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/AuthViewModel.kt)**: Manages authentication state, login/logout, and profile updates.
- **[NEW] [ContactsViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/ContactsViewModel.kt)**: Manages contact list, searching, and online status syncing.
- **[NEW] [CallViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/CallViewModel.kt)**: Manages LiveKit room state, track publication, and UI controls (mute/camera).
- **[NEW] [HistoryViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/HistoryViewModel.kt)**: Manages call history UI state.

### 4. UI Layer Refactoring
- **[MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)**:
    - Annotate with `@AndroidEntryPoint`.
    - Inject ViewModels.
    - Replace direct logic calls with ViewModel method calls.
    - Observe `StateFlow` updates for UI changes.
- **[MODIFY] [IncomingCallActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/IncomingCallActivity.kt)**: Refactor to use ViewModels.

## Verification Plan

### Automated Tests
- Run Gradle build to verify dependency injection setup.
- (Optional) Add unit tests for ViewModels.

### Manual Verification
1.  **Login/Auth**: Verify login, session persistence, and logout still work correctly.
2.  **Contacts**: Verify contact list loads, searches correctly, and shows online status.
3.  **Calling**: Verify starting a call, receiving a call, and the in-call UI (mute, camera, speaker) still work.
4.  **History**: Verify call logs are saved and displayed correctly.
5.  **Themes**: Verify dark/light theme switching still works through the ViewModel.
