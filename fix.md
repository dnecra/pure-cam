

Feature Request \& Bug Fix: Camera Orientation and UI Overhaul





1\. Bug Fixes: Camera Orientation \& Rotation



• Front Camera Viewfinder Mirroring/Inversion: When the front camera is active and the phone is held horizontally, the viewfinder displays the image rotated by 180 degrees (upside down). Fix the viewfinder orientation calculation so the preview remains right-side up in landscape mode.

• Dynamic Image Capture Rotation: Ensure that the physical phone orientation at the moment of capture dynamically determines the EXIF rotation metadata of the saved image file.



2\. UI Modfications (Jetpack Compose)





A. Viewfinder Layout (GoogleCameraUI.kt)



• Remove Top Padding: Remove the top = 8.dp padding from the viewfinder card. Update the modifier to:kotlin

.padding(start = 10.dp, end = 10.dp, bottom = 88.dp)

Use code with caution.



&#x09;• Result: The card must sit directly flush under the status bar. The top scrim, flash, and timer rows should rest against the status bar without a visible gap.



B. Component Resizing (SegmentedControl.kt)



• Height Adjustment: Increase the overall SegmentedControl height from 40.dp to 48.dp.

• Button Shape \& Size: Update SegmentButton properties:

&#x09;• Change shape from RoundedCornerShape(20.dp) to CircleShape.

&#x09;• Resize dimensions from 46.dp x 34.dp to 48.dp x 48.dp to create perfect circles.

• Icon Scaling: Scale the internal button icons up from 20.dp to 24.dp to match the settings and White Balance (WB) button icons.

&#x09;• Note: Keep the outer tray (Surface) rounded styling unchanged.



C. Layout Compaction \& Centering



• Bottom Controls Stack (GoogleCameraUI.kt):

&#x09;• Refactor the Gallery / Shutter / Flip button Row.

&#x09;• Remove the edge-pushing weight(1f) modifiers (Alignment.CenterStart / Alignment.CenterEnd).

&#x09;• Implement Arrangement.Center with 24.dp horizontal spacers between the components so the gallery and flip buttons pull inward toward the shutter button.

• Bottom Navigation Bar:

&#x09;• Wrap the three main sections (Settings, Mode Toggle, WB) in individual 48.dp Box layouts.

&#x09;• Arrange them as a centered group using Arrangement.Center with 24.dp spacing gaps and 16.dp padding. This pulls the Settings and WB options away from the outer edges toward the center.



