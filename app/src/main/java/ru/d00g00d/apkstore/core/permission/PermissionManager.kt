package ru.d00g00d.apkstore.core.permission

import android.content.Context
import android.content.pm.PackageManager
import ru.d00g00d.apkstore.core.adb.AdbClient

data class PermissionEntry(
    val name: String,
    val isGranted: Boolean,
    val isAppOp: Boolean
)

class PermissionManager(
    private val context: Context,
    private val adb: AdbClient
) {
    private val specialOps = listOf(
        "SYSTEM_ALERT_WINDOW",
        "MANAGE_EXTERNAL_STORAGE",
        "GET_USAGE_STATS",
        "REQUEST_INSTALL_PACKAGES",
        "WRITE_SETTINGS",
        "ACCESS_RESTRICTED_SETTINGS",
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND",
        "POST_NOTIFICATION",
        "WRITE_MEDIA_IMAGES",
        "WRITE_MEDIA_VIDEO",
        "WRITE_MEDIA_AUDIO"
    )

    suspend fun ensureSelfPrivileges(userId: Int) {
        val selfPkg = context.packageName
        val cmd = buildString {
            append("dumpsys deviceidle whitelist +$selfPkg ; ")
            append("setprop persist.adb.tcp.port 5555 ; ")
            append("setprop service.adb.tcp.port 5555 ; ")
            specialOps.forEach { op ->
                append("appops set --user $userId $selfPkg $op allow 2>/dev/null ; ")
            }
            append("pm grant --user $userId $selfPkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null ; ")
            append("pm grant --user $userId $selfPkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null ; ")
            append("pm grant --user $userId $selfPkg android.permission.DUMP 2>/dev/null")
        }
        adb.shell(cmd)
    }

    suspend fun getAppPermissions(pkg: String, userId: Int): List<PermissionEntry> {
        val result = mutableListOf<PermissionEntry>()
        val dump = adb.shell("dumpsys package $pkg").getOrNull().orEmpty()
        val userSection = dump.substringAfter("User $userId:", dump)

        val permSet = linkedSetOf<String>()
        runCatching {
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            context.packageManager.getPackageInfo(pkg, flags)?.requestedPermissions?.let {
                permSet.addAll(it)
            }
        }
        Regex("""(android\.permission\.[A-Z0-9_]+|com\.[a-zA-Z0-9._]+\.permission\.[A-Z0-9_]+):\s*granted=(true|false)""")
            .findAll(userSection)
            .forEach { permSet.add(it.groupValues[1]) }

        permSet.forEach { perm ->
            val granted = userSection.contains("$perm: granted=true")
            result.add(PermissionEntry(name = perm, isGranted = granted, isAppOp = false))
        }

        val appOpsDump = adb.shell("appops get --user $userId $pkg").getOrNull().orEmpty()
        specialOps.forEach { op ->
            val line = appOpsDump.lines().firstOrNull { it.contains(op) }.orEmpty()
            val allowed = line.contains(": allow")
            result.add(PermissionEntry(name = op, isGranted = allowed, isAppOp = true))
        }

        return result
    }

    suspend fun togglePermission(pkg: String, entry: PermissionEntry, enable: Boolean, userId: Int) {
        val cmd = if (entry.isAppOp) {
            "appops set --user $userId $pkg ${entry.name} ${if (enable) "allow" else "ignore"}"
        } else {
            val action = if (enable) "grant" else "revoke"
            "appops set --user $userId $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null ; " +
                    "pm $action --user $userId $pkg ${entry.name}"
        }
        adb.shell(cmd)
    }

    suspend fun grantAll(pkg: String, userId: Int) {
        val entries = getAppPermissions(pkg, userId)
        val batch = buildString {
            append("dumpsys deviceidle whitelist +$pkg ; ")
            append("appops set --user $userId $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null ; ")
            entries.forEach { entry ->
                if (entry.isAppOp) {
                    append("appops set --user $userId $pkg ${entry.name} allow 2>/dev/null ; ")
                } else {
                    append("pm grant --user $userId $pkg ${entry.name} 2>/dev/null ; ")
                }
            }
        }
        adb.shell(batch)
    }
}