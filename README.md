# AutoJoystick

Overlay + on-device ML Kit OCR + AccessibilityService joystick drag. Reads minimap coords like `(51,35)` and autopilots the in-game joystick so the character walks toward the target.

## Required permissions (all manual, by design — Android blocks automatic granting)

1. **Display over other apps** → Settings → Apps → AutoJoystick → Special access
2. **Accessibility** → Settings → Accessibility → AutoJoystick → enable
3. **MediaProjection consent** → shown by app at first START

## Coordinate calibration

- The first time you SCP into your device, crop the top-right HUD region (where `(X,Y)` lives) and store it in `CoordinateCalibrator.coordRect`.
- The first time you tap the joystick, drop the in-game joystick base center + radius into `JoystickController`.

## Build

```
./gradlew assembleDebug
```

CI: `.github/workflows/build.yml` produces a debug APK on every push to `main`. The resulting artifact is `app-debug.apk`.
