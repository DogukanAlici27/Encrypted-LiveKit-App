# Walkthrough - MVVM & Hilt Architecture Refactor

I have successfully refactored the project to follow the **MVVM (Model-View-ViewModel)** architecture and integrated **Hilt** for Dependency Injection. This change significantly improves the code maintainability and testability.

## Key Changes

### 1. Dependency Injection (Hilt)
- **[LiveKitApplication.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/LiveKitApplication.kt)**: Created the application class with `@HiltAndroidApp`.
- **[AppModule.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/di/AppModule.kt)**: Established Hilt module to provide singleton dependencies like `AppDatabase`, `SessionPreferences`, and `OkHttpClient`.
- **Plugin Setup**: Configured `libs.versions.toml` and `build.gradle.kts` with Hilt and KSP plugins.

### 2. Data Layer (Repository Pattern)
- **[UserRepository.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/data/repository/UserRepository.kt)**: Converted the previous `UserRepository` object into an injectable class. It now receives dependencies (DB, Prefs, Network) via constructor injection.
- **[SessionPreferences.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/pref/SessionPreferences.kt)**: Updated to be injectable with Hilt.

### 3. ViewModel Layer
Created specialized ViewModels to handle business logic and UI state:
- **[AuthViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/AuthViewModel.kt)**: Handles authentication, password changes, and account management using `StateFlow` and `SharedFlow`.
- **[ContactsViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/ContactsViewModel.kt)**: Manages contact list synchronization and database observation.
- **[CallViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/CallViewModel.kt)**: Manages room state and basic call controls (mute/camera).
- **[HistoryViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/HistoryViewModel.kt)**: Provides call history logs from the database.

### 4. UI Layer Refactoring
- **[MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)**:
    - Added `@AndroidEntryPoint`.
    - Injected ViewModels using `by viewModels()`.
    - Implemented `observeViewModel()` to update UI based on `StateFlow` changes.
    - Moved authentication and contact loading logic to ViewModels.
- **[IncomingCallActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/IncomingCallActivity.kt)**: Integrated with Hilt and injected repositories.
- **[MyFirebaseMessagingService.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/MyFirebaseMessagingService.kt)**: Updated to support Hilt injection.

### 5. Worker Refactoring
- **[HeartbeatWorker.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/worker/HeartbeatWorker.kt)** & **[UserSyncWorker.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/worker/UserSyncWorker.kt)**: Refactored to use `@HiltWorker` and `@AssistedInject`.

## Verification Results

### Build Verification
- Updated all dependencies and plugins.
- Resolved all conflicting imports and static object calls.

### Architectural Improvements
- **Decoupling**: Activities no longer interact directly with the database or network clients.
- **Consistency**: All data flows through Repositories to ViewModels, then to the UI.
- **Modern State Management**: Replaced manual UI updates with reactive `collect` calls on `StateFlow`.
