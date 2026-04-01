#!/system/bin/sh
##########################################################################################
#
# PrivateSpaceMod — Magisk Module Installer
#
# Installs the PrivateSpaceMod APK as a privileged system app so it gets:
#   - MANAGE_USERS
#   - INTERACT_ACROSS_USERS
#   - INTERACT_ACROSS_USERS_FULL
#   - SYSTEM_ALERT_WINDOW
#
# Also installs the privapp-permissions whitelist.
#
##########################################################################################

SKIPUNZIP=1

ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print "  PrivateSpaceMod v2.0"
ui_print "  Double-tap unlock + Label hider"
ui_print "  + App picker + QS tile"
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print ""

# Extract module files
ui_print "- Extracting module files..."
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >/dev/null 2>&1
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >/dev/null 2>&1

# Create priv-app directory
mkdir -p "$MODPATH/system/priv-app/PrivateSpaceMod"
ui_print "- Installing privileged app..."

# Extract APK
unzip -o "$ZIPFILE" 'system/priv-app/PrivateSpaceMod/PrivateSpaceMod.apk' -d "$MODPATH" >/dev/null 2>&1

if [ ! -f "$MODPATH/system/priv-app/PrivateSpaceMod/PrivateSpaceMod.apk" ]; then
    ui_print "! ERROR: APK not found in ZIP"
    ui_print "! Make sure the CI build completed successfully"
    exit 1
fi

# Install privapp-permissions whitelist
mkdir -p "$MODPATH/system/etc/permissions"
unzip -o "$ZIPFILE" 'system/etc/permissions/privapp-permissions-privatespacmod.xml' -d "$MODPATH" >/dev/null 2>&1

if [ ! -f "$MODPATH/system/etc/permissions/privapp-permissions-privatespacmod.xml" ]; then
    # Write it inline as fallback
    ui_print "- Creating permissions whitelist inline..."
    cat > "$MODPATH/system/etc/permissions/privapp-permissions-privatespacmod.xml" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.fdavids77.privatespacmod">
        <permission name="android.permission.MANAGE_USERS" />
        <permission name="android.permission.INTERACT_ACROSS_USERS" />
        <permission name="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </privapp-permissions>
</permissions>
XMLEOF
fi

# Set permissions
ui_print "- Setting permissions..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/priv-app/PrivateSpaceMod/PrivateSpaceMod.apk" 0 0 0644

ui_print ""
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ui_print "  Installation complete!"
ui_print ""
ui_print "  NEXT STEPS:"
ui_print "  1. Reboot"
ui_print "  2. Enable in LSPosed Manager:"
ui_print "     → Modules → PrivateSpaceMod"
ui_print "     → Scope: Pixel Launcher"
ui_print "  3. Add QS tile (optional backup):"
ui_print "     → Swipe down → Edit → Private Space"
ui_print "  4. Double-tap home screen to unlock!"
ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
