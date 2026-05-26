# Diff Details

Date : 2026-05-27 00:18:11

Directory d:\\code\\accessible_dialer

Total : 37 files,  8655 codes, 937 comments, 567 blanks, all 10159 lines

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details

## Files
| filename | language | code | comment | blank | total |
| :--- | :--- | ---: | ---: | ---: | ---: |
| [app/build.gradle.kts](/app/build.gradle.kts) | Kotlinscript | 49 | 1 | 12 | 62 |
| [app/src/main/java/com/accessible/dialer/DialerApplication.kt](/app/src/main/java/com/accessible/dialer/DialerApplication.kt) | Kotlin | 9 | 4 | 3 | 16 |
| [app/src/main/java/com/accessible/dialer/MainActivity.kt](/app/src/main/java/com/accessible/dialer/MainActivity.kt) | Kotlin | 115 | 12 | 15 | 142 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockedNumbersRepository.kt) | Kotlin | 105 | 22 | 11 | 138 |
| [app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt](/app/src/main/java/com/accessible/dialer/blocking/BlockingCallScreeningService.kt) | Kotlin | 41 | 17 | 6 | 64 |
| [app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt](/app/src/main/java/com/accessible/dialer/blocking/QuietHours.kt) | Kotlin | 48 | 23 | 11 | 82 |
| [app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt](/app/src/main/java/com/accessible/dialer/call/DialerInCallService.kt) | Kotlin | 42 | 11 | 10 | 63 |
| [app/src/main/java/com/accessible/dialer/call/InCallActivity.kt](/app/src/main/java/com/accessible/dialer/call/InCallActivity.kt) | Kotlin | 41 | 10 | 5 | 56 |
| [app/src/main/java/com/accessible/dialer/call/InCallScreen.kt](/app/src/main/java/com/accessible/dialer/call/InCallScreen.kt) | Kotlin | 490 | 54 | 16 | 560 |
| [app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt](/app/src/main/java/com/accessible/dialer/call/OngoingCallHolder.kt) | Kotlin | 83 | 21 | 17 | 121 |
| [app/src/main/java/com/accessible/dialer/call/Ringer.kt](/app/src/main/java/com/accessible/dialer/call/Ringer.kt) | Kotlin | 112 | 19 | 12 | 143 |
| [app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt](/app/src/main/java/com/accessible/dialer/settings/SettingsRepository.kt) | Kotlin | 132 | 29 | 28 | 189 |
| [app/src/main/java/com/accessible/dialer/ui/DialerApp.kt](/app/src/main/java/com/accessible/dialer/ui/DialerApp.kt) | Kotlin | 324 | 45 | 13 | 382 |
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
| [app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt](/app/src/main/java/com/accessible/dialer/ui/settings/SettingsScreen.kt) | Kotlin | 326 | 7 | 17 | 350 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Theme.kt) | Kotlin | 58 | 3 | 6 | 67 |
| [app/src/main/java/com/accessible/dialer/ui/theme/Type.kt](/app/src/main/java/com/accessible/dialer/ui/theme/Type.kt) | Kotlin | 15 | 1 | 3 | 19 |
| [app/src/main/java/com/accessible/dialer/util/ContactOps.kt](/app/src/main/java/com/accessible/dialer/util/ContactOps.kt) | Kotlin | 295 | 54 | 20 | 369 |
| [app/src/main/java/com/accessible/dialer/util/ContactPorting.kt](/app/src/main/java/com/accessible/dialer/util/ContactPorting.kt) | Kotlin | 170 | 31 | 19 | 220 |
| [app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt](/app/src/main/java/com/accessible/dialer/util/DefaultDialer.kt) | Kotlin | 19 | 8 | 5 | 32 |
| [app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt](/app/src/main/java/com/accessible/dialer/util/DialerPermissions.kt) | Kotlin | 18 | 5 | 5 | 28 |
| [app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt](/app/src/main/java/com/accessible/dialer/util/PhoneAccounts.kt) | Kotlin | 41 | 15 | 6 | 62 |
| [app/src/main/java/com/accessible/dialer/util/RowActions.kt](/app/src/main/java/com/accessible/dialer/util/RowActions.kt) | Kotlin | 54 | 16 | 7 | 77 |
| [app/src/main/res/values/strings.xml](/app/src/main/res/values/strings.xml) | XML | 6 | 0 | 0 | 6 |
| [build.gradle.kts](/build.gradle.kts) | Kotlinscript | 4 | 0 | 1 | 5 |
| [settings.gradle.kts](/settings.gradle.kts) | Kotlinscript | 16 | 0 | 2 | 18 |

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details