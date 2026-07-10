#!/bin/sh
# Cold-boot the Pixel_9_Pro AVD (always correct time — no stale quickboot snapshot),
# then strip the huge rounded corners and animations that a cold boot re-enables.

AVD=Pixel_9_Pro
SERIAL=emulator-5554

# Launch only if it isn't already up.
if ! adb devices | grep -q "^$SERIAL[[:space:]]*device"; then
    echo ">>> Cold-booting $AVD ..."
    # -no-snapshot: never load/save quickboot -> fresh clock every time.
    # -fixed-scale + QT_ENABLE_HIGHDPI_SCALING=0: keep the window 1:1 on FullHD.
    QT_ENABLE_HIGHDPI_SCALING=0 ~/Android/Sdk/emulator/emulator \
        -avd "$AVD" -no-snapshot -fixed-scale >/dev/null 2>&1 &

    echo ">>> Waiting for boot ..."
    adb -s "$SERIAL" wait-for-device
    until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 2
    done
else
    echo ">>> $SERIAL already running — applying fixups only."
fi

echo ">>> Disabling rounded-corner overlays ..."
adb -s "$SERIAL" shell cmd overlay disable com.android.internal.emulation.pixel_9_pro
adb -s "$SERIAL" shell cmd overlay disable com.android.systemui.emulation.pixel_9_pro
adb -s "$SERIAL" shell am force-stop com.android.systemui

echo ">>> Disabling animations ..."
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb -s "$SERIAL" shell settings put global "$s" 0
done

echo ">>> Ready."
adb -s "$SERIAL" shell cmd overlay list | grep -E "emulation.pixel_9_pro$"
