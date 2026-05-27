# Details

Date : 2026-05-27 21:59:07

Directory d:\\code\\accessible_dialer

Total : 70 files,  14195 codes, 2062 comments, 1045 blanks, all 17302 lines

[Summary](results.md) / Details / [Diff Summary](diff.md) / [Diff Details](diff-details.md)

## Files
| filename | language | code | comment | blank | total |
| :--- | :--- | ---: | ---: | ---: | ---: |
| [README.md](/README.md) | Markdown | 62 | 0 | 14 | 76 |
| [app/build.gradle.kts](/app/build.gradle.kts) | Kotlinscript | 71 | 7 | 15 | 93 |
| [app/src/main/AndroidManifest.xml](/app/src/main/AndroidManifest.xml) | XML | 96 | 23 | 9 | 128 |
| [app/src/main/java/com/accessible/dialer/DialerApplication.kt](/app/src/main/java/com/accessible/dialer/DialerApplication.kt) | Kotlin | 9 | 4 | 3 | 16 |
| [app/src/main/java/com/accessible/dialer/MainActivity.kt](/app/src/main/java/com/accessible/dialer/MainActivity.kt) | Kotlin | 257 | 48 | 20 | 325 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt) | Kotlin | 105 | 22 | 11 | 138 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt) | Kotlin | 43 | 34 | 7 | 84 |
| [app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt](/app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt) | Kotlin | 48 | 23 | 11 | 82 |
| [app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt](/app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt) | Kotlin | 233 | 125 | 26 | 384 |
| [app/src/main/java/com/accessible/dialer/call/InCallActivity.kt](/app/src/main/java/com/accessible/dialer/call/InCallActivity.kt) | Kotlin | 67 | 31 | 7 | 105 |
| [app/src/main/java/com/accessible/dialer/call/InCallScreen.kt](/app/src/main/java/com/accessible/dialer/call/InCallScreen.kt) | Kotlin | 542 | 69 | 17 | 628 |
| [app/src/main/java/com/accessible/dialer/call/MissedCallNotifier.kt](/app/src/main/java/com/accessible/dialer/call/MissedCallNotifier.kt) | Kotlin | 123 | 46 | 20 | 189 |
| [app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt](/app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt) | Kotlin | 83 | 21 | 17 | 121 |
| [app/src/main/java/com/accessible/dialer/call/ProximitySpeakerController.kt](/app/src/main/java/com/accessible/dialer/call/ProximitySpeakerController.kt) | Kotlin | 44 | 13 | 8 | 65 |
| [app/src/main/java/com/accessible/dialer/call/RingVolumeKeyInterceptor.kt](/app/src/main/java/com/accessible/dialer/call/RingVolumeKeyInterceptor.kt) | Kotlin | 57 | 19 | 7 | 83 |
| [app/src/main/java/com/accessible/dialer/call/Ringer.kt](/app/src/main/java/com/accessible/dialer/call/Ringer.kt) | Kotlin | 121 | 27 | 12 | 160 |
| [app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt](/app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt) | Kotlin | 230 | 62 | 46 | 338 |
| [app/src/main/java/com/accessible/dialer/ui/DialerApp.kt](/app/src/main/java/com/accessible/dialer/ui/DialerApp.kt) | Kotlin | 511 | 95 | 25 | 631 |
| [app/src/main/java/com/accessible/dialer/ui/blocking/BlockedNumbersScreen.kt](/app/src/main/java/com/accessible/dialer/ui/blocking/BlockedNumbersScreen.kt) | Kotlin | 181 | 0 | 9 | 190 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactDetailsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactDetailsScreen.kt) | Kotlin | 1,375 | 103 | 66 | 1,544 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactEditorScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactEditorScreen.kt) | Kotlin | 954 | 83 | 47 | 1,084 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactPickerDialog.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactPickerDialog.kt) | Kotlin | 143 | 8 | 4 | 155 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactsScreen.kt) | Kotlin | 652 | 63 | 20 | 735 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactsViewModel.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactsViewModel.kt) | Kotlin | 160 | 19 | 13 | 192 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateDismissals.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateDismissals.kt) | Kotlin | 20 | 6 | 6 | 32 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateScanScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateScanScreen.kt) | Kotlin | 573 | 38 | 27 | 638 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/NameFixScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/NameFixScreen.kt) | Kotlin | 912 | 86 | 65 | 1,063 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/NameNormalizeScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/NameNormalizeScreen.kt) | Kotlin | 574 | 136 | 30 | 740 |
| [app/src/main/java/com/accessible/dialer/ui/dialpad/DialpadScreen.kt](/app/src/main/java/com/accessible/dialer/ui/dialpad/DialpadScreen.kt) | Kotlin | 615 | 117 | 28 | 760 |
| [app/src/main/java/com/accessible/dialer/ui/favorites/FavoritesScreen.kt](/app/src/main/java/com/accessible/dialer/ui/favorites/FavoritesScreen.kt) | Kotlin | 60 | 0 | 6 | 66 |
| [app/src/main/java/com/accessible/dialer/ui/help/UserGuideScreen.kt](/app/src/main/java/com/accessible/dialer/ui/help/UserGuideScreen.kt) | Kotlin | 106 | 8 | 7 | 121 |
| [app/src/main/java/com/accessible/dialer/ui/recents/RecentsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/recents/RecentsScreen.kt) | Kotlin | 556 | 82 | 19 | 657 |
| [app/src/main/java/com/accessible/dialer/ui/recents/RecentsViewModel.kt](/app/src/main/java/com/accessible/dialer/ui/recents/RecentsViewModel.kt) | Kotlin | 318 | 122 | 20 | 460 |
| [app/src/main/java/com/accessible/dialer/ui/settings/AccessibilityScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/AccessibilityScreen.kt) | Kotlin | 145 | 4 | 8 | 157 |
| [app/src/main/java/com/accessible/dialer/ui/settings/BlockingScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/BlockingScreen.kt) | Kotlin | 116 | 5 | 6 | 127 |
| [app/src/main/java/com/accessible/dialer/ui/settings/CallingScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/CallingScreen.kt) | Kotlin | 130 | 5 | 7 | 142 |
| [app/src/main/java/com/accessible/dialer/ui/settings/DisplayScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/DisplayScreen.kt) | Kotlin | 91 | 5 | 6 | 102 |
| [app/src/main/java/com/accessible/dialer/ui/settings/RingtonesScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/RingtonesScreen.kt) | Kotlin | 107 | 7 | 6 | 120 |
| [app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt) | Kotlin | 568 | 31 | 21 | 620 |
| [app/src/main/java/com/accessible/dialer/ui/settings/ToolsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/ToolsScreen.kt) | Kotlin | 139 | 11 | 6 | 156 |
| [app/src/main/java/com/accessible/dialer/ui/storage/StorageLocationsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/storage/StorageLocationsScreen.kt) | Kotlin | 411 | 41 | 20 | 472 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt) | Kotlin | 58 | 3 | 6 | 67 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Type.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Type.kt) | Kotlin | 15 | 1 | 3 | 19 |
| [app/src/main/java/com/accessible/dialer/util/ContactAccounts.kt](/app/src/main/java/com/accessible/dialer/util/ContactAccounts.kt) | Kotlin | 208 | 87 | 15 | 310 |
| [app/src/main/java/com/accessible/dialer/util/ContactOps.kt](/app/src/main/java/com/accessible/dialer/util/ContactOps.kt) | Kotlin | 300 | 59 | 21 | 380 |
| [app/src/main/java/com/accessible/dialer/util/ContactPorting.kt](/app/src/main/java/com/accessible/dialer/util/ContactPorting.kt) | Kotlin | 170 | 31 | 19 | 220 |
| [app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt](/app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt) | Kotlin | 19 | 8 | 5 | 32 |
| [app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt](/app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt) | Kotlin | 18 | 5 | 5 | 28 |
| [app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt](/app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt) | Kotlin | 41 | 15 | 6 | 62 |
| [app/src/main/java/com/accessible/dialer/util/RowActions.kt](/app/src/main/java/com/accessible/dialer/util/RowActions.kt) | Kotlin | 68 | 21 | 8 | 97 |
| [app/src/main/java/com/accessible/dialer/util/ShakeDetector.kt](/app/src/main/java/com/accessible/dialer/util/ShakeDetector.kt) | Kotlin | 53 | 19 | 10 | 82 |
| [app/src/main/java/com/accessible/dialer/voice/VoiceSearch.kt](/app/src/main/java/com/accessible/dialer/voice/VoiceSearch.kt) | Kotlin | 131 | 46 | 22 | 199 |
| [app/src/main/java/com/accessible/dialer/voice/VoiceSearchSheet.kt](/app/src/main/java/com/accessible/dialer/voice/VoiceSearchSheet.kt) | Kotlin | 162 | 26 | 9 | 197 |
| [app/src/main/res/drawable/ic\_launcher\_foreground.xml](/app/src/main/res/drawable/ic_launcher_foreground.xml) | XML | 9 | 0 | 1 | 10 |
| [app/src/main/res/mipmap-anydpi-v26/ic\_launcher.xml](/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) | XML | 5 | 0 | 1 | 6 |
| [app/src/main/res/mipmap-anydpi-v26/ic\_launcher\_round.xml](/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml) | XML | 5 | 0 | 1 | 6 |
| [app/src/main/res/values-ar/strings.xml](/app/src/main/res/values-ar/strings.xml) | XML | 168 | 13 | 27 | 208 |
| [app/src/main/res/values-de/strings.xml](/app/src/main/res/values-de/strings.xml) | XML | 172 | 2 | 27 | 201 |
| [app/src/main/res/values-es/strings.xml](/app/src/main/res/values-es/strings.xml) | XML | 168 | 2 | 27 | 197 |
| [app/src/main/res/values-fr/strings.xml](/app/src/main/res/values-fr/strings.xml) | XML | 168 | 12 | 27 | 207 |
| [app/src/main/res/values/colors.xml](/app/src/main/res/values/colors.xml) | XML | 4 | 0 | 1 | 5 |
| [app/src/main/res/values/strings.xml](/app/src/main/res/values/strings.xml) | XML | 542 | 30 | 49 | 621 |
| [app/src/main/res/values/themes.xml](/app/src/main/res/values/themes.xml) | XML | 8 | 1 | 1 | 10 |
| [build.gradle.kts](/build.gradle.kts) | Kotlinscript | 4 | 0 | 1 | 5 |
| [gradle.properties](/gradle.properties) | Properties | 4 | 0 | 1 | 5 |
| [gradle/wrapper/gradle-wrapper.properties](/gradle/wrapper/gradle-wrapper.properties) | Properties | 7 | 0 | 1 | 8 |
| [gradlew.bat](/gradlew.bat) | Batch | 41 | 30 | 22 | 93 |
| [scripts/run-and-tail.ps1](/scripts/run-and-tail.ps1) | PowerShell | 22 | 2 | 5 | 29 |
| [settings.gradle.kts](/settings.gradle.kts) | Kotlinscript | 16 | 0 | 2 | 18 |
| [ui.xml](/ui.xml) | XML | 1 | 0 | 0 | 1 |

[Summary](results.md) / Details / [Diff Summary](diff.md) / [Diff Details](diff-details.md)