# Implementation Plan - Vibrant Notification Bell with Toggle

Improve the notification bell icon on the dashboard by making it more visible (vibrant yellow) and adding the ability to toggle notifications on/off.

## Proposed Changes

### Assets

#### [NEW] [ic_notification_on.xml](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/res/drawable/ic_notification_on.xml)
Create a new vector drawable for the "Notifications On" state (a yellow bell).

#### [NEW] [ic_notification_off.xml](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/res/drawable/ic_notification_off.xml)
Create a new vector drawable for the "Notifications Off" state (a yellow bell with a strike-through).

### Layouts

#### [MODIFY] [activity_admin_dashboard.xml](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/res/layout/activity_admin_dashboard.xml)
- Replace the `TextView` (id: `notificationButton`) with an `ImageView`.
- Ensure it has proper padding and size for a better touch target.

### Logic

#### [MODIFY] [AdminNotificationListener.kt](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/java/com/example/eboneadminpanel/AdminNotificationListener.kt)
- Add a mechanism to check if notifications are enabled before showing them.
- This can be done by checking `SharedPreferences` within the `showNotification` method.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Dell/AndroidStudioProjects/EboneAdminPanel/app/src/main/java/com/example/eboneadminpanel/MainActivity.kt)
- Initialize the bell icon's state from `SharedPreferences` in `onCreate`.
- Implement the click listener for the new bell `ImageView` to toggle the state, update the icon, and save to `SharedPreferences`.

## Verification Plan

### Automated Tests
- Build the project to ensure no resource errors.

### Manual Verification
1. Open the app and observe the new yellow bell icon in the dashboard header.
2. Tap the bell icon:
   - Verify it changes to the "Notifications Off" icon.
   - Verify a toast message or visual feedback indicates notifications are off.
3. Tap it again:
   - Verify it changes back to the "Notifications On" icon.
4. Test notifications (if possible) to ensure they are only shown when enabled.
5. Restart the app and verify the bell state is persisted.
