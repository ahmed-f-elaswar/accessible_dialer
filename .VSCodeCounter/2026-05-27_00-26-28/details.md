# Details

Date : 2026-05-27 00:26:28

Directory d:\\code\\accessible_dialer

Total : 49 files,  9547 codes, 1016 comments, 658 blanks, all 11221 lines

[Summary](results.md) / Details / [Diff Summary](diff.md) / [Diff Details](diff-details.md)

## Files
| filename | language | code | comment | blank | total |
| :--- | :--- | ---: | ---: | ---: | ---: |
| [README.md](/README.md) | Markdown | 62 | 0 | 14 | 76 |
| [app/build.gradle.kts](/app/build.gradle.kts) | Kotlinscript | 49 | 1 | 12 | 62 |
| [app/src/main/AndroidManifest.xml](/app/src/main/AndroidManifest.xml) | XML | 90 | 16 | 8 | 114 |
| [app/src/main/java/com/accessible/dialer/DialerApplication.kt](/app/src/main/java/com/accessible/dialer/DialerApplication.kt) | Kotlin | 9 | 4 | 3 | 16 |
| [app/src/main/java/com/accessible/dialer/MainActivity.kt](/app/src/main/java/com/accessible/dialer/MainActivity.kt) | Kotlin | 158 | 18 | 17 | 193 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt) | Kotlin | 105 | 22 | 11 | 138 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt) | Kotlin | 41 | 17 | 6 | 64 |
| [app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt](/app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt) | Kotlin | 48 | 23 | 11 | 82 |
| [app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt](/app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt) | Kotlin | 42 | 11 | 10 | 63 |
| [app/src/main/java/com/accessible/dialer/call/InCallActivity.kt](/app/src/main/java/com/accessible/dialer/call/InCallActivity.kt) | Kotlin | 41 | 10 | 5 | 56 |
| [app/src/main/java/com/accessible/dialer/call/InCallScreen.kt](/app/src/main/java/com/accessible/dialer/call/InCallScreen.kt) | Kotlin | 490 | 54 | 16 | 560 |
| [app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt](/app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt) | Kotlin | 83 | 21 | 17 | 121 |
| [app/src/main/java/com/accessible/dialer/call/Ringer.kt](/app/src/main/java/com/accessible/dialer/call/Ringer.kt) | Kotlin | 112 | 19 | 12 | 143 |
| [app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt](/app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt) | Kotlin | 132 | 29 | 28 | 189 |
| [app/src/main/java/com/accessible/dialer/ui/DialerApp.kt](/app/src/main/java/com/accessible/dialer/ui/DialerApp.kt) | Kotlin | 312 | 48 | 13 | 373 |
| [app/src/main/java/com/accessible/dialer/ui/blocking/BlockedNumbersScreen.kt](/app/src/main/java/com/accessible/dialer/ui/blocking/BlockedNumbersScreen.kt) | Kotlin | 239 | 0 | 11 | 250 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactDetailsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactDetailsScreen.kt) | Kotlin | 1,366 | 98 | 66 | 1,530 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactEditorScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactEditorScreen.kt) | Kotlin | 847 | 29 | 44 | 920 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactsScreen.kt) | Kotlin | 592 | 47 | 20 | 659 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/ContactsViewModel.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/ContactsViewModel.kt) | Kotlin | 160 | 19 | 13 | 192 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateDismissals.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateDismissals.kt) | Kotlin | 20 | 6 | 6 | 32 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateScanScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/DuplicateScanScreen.kt) | Kotlin | 573 | 38 | 27 | 638 |
| [app/src/main/java/com/accessible/dialer/ui/contacts/NameFixScreen.kt](/app/src/main/java/com/accessible/dialer/ui/contacts/NameFixScreen.kt) | Kotlin | 912 | 86 | 65 | 1,063 |
| [app/src/main/java/com/accessible/dialer/ui/dialpad/DialpadScreen.kt](/app/src/main/java/com/accessible/dialer/ui/dialpad/DialpadScreen.kt) | Kotlin | 550 | 95 | 26 | 671 |
| [app/src/main/java/com/accessible/dialer/ui/favorites/FavoritesScreen.kt](/app/src/main/java/com/accessible/dialer/ui/favorites/FavoritesScreen.kt) | Kotlin | 60 | 0 | 6 | 66 |
| [app/src/main/java/com/accessible/dialer/ui/recents/RecentsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/recents/RecentsScreen.kt) | Kotlin | 538 | 59 | 19 | 616 |
| [app/src/main/java/com/accessible/dialer/ui/recents/RecentsViewModel.kt](/app/src/main/java/com/accessible/dialer/ui/recents/RecentsViewModel.kt) | Kotlin | 185 | 52 | 14 | 251 |
| [app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt) | Kotlin | 538 | 12 | 25 | 575 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt) | Kotlin | 58 | 3 | 6 | 67 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Type.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Type.kt) | Kotlin | 15 | 1 | 3 | 19 |
| [app/src/main/java/com/accessible/dialer/util/ContactOps.kt](/app/src/main/java/com/accessible/dialer/util/ContactOps.kt) | Kotlin | 295 | 54 | 20 | 369 |
| [app/src/main/java/com/accessible/dialer/util/ContactPorting.kt](/app/src/main/java/com/accessible/dialer/util/ContactPorting.kt) | Kotlin | 170 | 31 | 19 | 220 |
| [app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt](/app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt) | Kotlin | 19 | 8 | 5 | 32 |
| [app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt](/app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt) | Kotlin | 18 | 5 | 5 | 28 |
| [app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt](/app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt) | Kotlin | 41 | 15 | 6 | 62 |
| [app/src/main/java/com/accessible/dialer/util/RowActions.kt](/app/src/main/java/com/accessible/dialer/util/RowActions.kt) | Kotlin | 54 | 16 | 7 | 77 |
| [app/src/main/res/drawable/ic\_launcher\_foreground.xml](/app/src/main/res/drawable/ic_launcher_foreground.xml) | XML | 9 | 0 | 1 | 10 |
| [app/src/main/res/mipmap-anydpi-v26/ic\_launcher.xml](/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) | XML | 5 | 0 | 1 | 6 |
| [app/src/main/res/mipmap-anydpi-v26/ic\_launcher\_round.xml](/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml) | XML | 5 | 0 | 1 | 6 |
| [app/src/main/res/values/colors.xml](/app/src/main/res/values/colors.xml) | XML | 4 | 0 | 1 | 5 |
| [app/src/main/res/values/strings.xml](/app/src/main/res/values/strings.xml) | XML | 397 | 16 | 25 | 438 |
| [app/src/main/res/values/themes.xml](/app/src/main/res/values/themes.xml) | XML | 8 | 1 | 1 | 10 |
| [build.gradle.kts](/build.gradle.kts) | Kotlinscript | 4 | 0 | 1 | 5 |
| [gradle.properties](/gradle.properties) | Properties | 4 | 0 | 1 | 5 |
| [gradle/wrapper/gradle-wrapper.properties](/gradle/wrapper/gradle-wrapper.properties) | Properties | 7 | 0 | 1 | 8 |
| [gradlew.bat](/gradlew.bat) | Batch | 41 | 30 | 22 | 93 |
| [scripts/run-and-tail.ps1](/scripts/run-and-tail.ps1) | PowerShell | 22 | 2 | 5 | 29 |
| [settings.gradle.kts](/settings.gradle.kts) | Kotlinscript | 16 | 0 | 2 | 18 |
| [ui.xml](/ui.xml) | XML | 1 | 0 | 0 | 1 |

[Summary](results.md) / Details / [Diff Summary](diff.md) / [Diff Details](diff-details.md)