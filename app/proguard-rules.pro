# Regimen R8 keep rules.
# Library consumer rules (kotlinx-serialization, WorkManager, ML Kit, AndroidX, Navigation 3)
# are merged automatically by AGP. Only add app-specific rules here.

# Navigation 3 uses kotlinx-serialization polymorphic lookup for NavKey subclasses during
# state restoration (rememberNavBackStack); keep Route implementations and their serializers.
-keep,includedescriptorclasses class com.exaclast.regimen.ui.navigation.Route** { *; }
