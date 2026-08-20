package dev.apum.analyze

import dev.apum.build.BuildInfo
import dev.apum.catalog.Protection
import dev.apum.manifest.ParsedManifest
import dev.apum.model.ComponentInfo
import dev.apum.model.Finding
import dev.apum.model.PermissionReport
import dev.apum.model.PermissionStatus
import dev.apum.model.Severity
import dev.apum.model.Usage
import dev.apum.source.SourceFile

class FindingEngine(
    private val buildInfo: BuildInfo,
    private val manifests: List<ParsedManifest>,
    private val permissions: List<PermissionReport>,
    private val components: List<ComponentInfo>,
    private val sourceFiles: List<SourceFile>
) {

    fun run(): List<Finding> {
        val findings = mutableListOf<Finding>()
        var counter = 0
        fun add(
            rule: String,
            severity: Severity,
            title: String,
            detail: String,
            recommendation: String,
            permission: String? = null,
            locations: List<Usage> = emptyList()
        ) {
            counter++
            findings.add(
                Finding(
                    id = "APUM-%03d".format(counter),
                    rule = rule,
                    severity = severity.label,
                    title = title,
                    detail = detail,
                    permission = permission,
                    recommendation = recommendation,
                    locations = locations.take(8)
                )
            )
        }

        permissions.forEach { report ->
            val dangerous = report.protection == Protection.DANGEROUS
            val special = report.protection == Protection.SPECIAL

            if (report.status == PermissionStatus.DECLARED_UNUSED) {
                add(
                    rule = "UNUSED_PERMISSION",
                    severity = if (dangerous || special) Severity.HIGH else Severity.MEDIUM,
                    title = "${report.shortName} is declared but never used in code",
                    detail = "${report.permission} is declared in the manifest, but no related API call, runtime " +
                        "permission request or permission constant reference was found in the code.",
                    recommendation = "Remove the permission from the manifest if it is not needed. If a library pulls it in, " +
                        "verify with the library documentation or strip it with tools:node=\"remove\".",
                    permission = report.permission,
                    locations = report.declarations.map { declaration ->
                        Usage(
                            file = declaration.file,
                            line = declaration.line,
                            module = declaration.module,
                            enclosing = "AndroidManifest",
                            symbol = report.shortName,
                            kind = UsageKind.MANIFEST,
                            evidence = "Manifest declaration",
                            confidence = "HIGH",
                            snippet = declaration.snippet
                        )
                    }
                )
            }

            if (report.status == PermissionStatus.UNDECLARED_USAGE) {
                add(
                    rule = "UNDECLARED_PERMISSION",
                    severity = Severity.CRITICAL,
                    title = "${report.shortName} is used in code but not declared in the manifest",
                    detail = "The code calls APIs that require this permission, but no matching uses-permission entry " +
                        "exists in AndroidManifest.xml. This throws SecurityException at runtime.",
                    recommendation = "Add the uses-permission entry to the manifest or remove the code path that needs it.",
                    permission = report.permission,
                    locations = report.usages
                )
            }

            if (dangerous && report.status == PermissionStatus.USED && report.runtimeRequests.isEmpty()) {
                add(
                    rule = "MISSING_RUNTIME_REQUEST",
                    severity = Severity.HIGH,
                    title = "${report.shortName} is a dangerous permission with no runtime request",
                    detail = "The permission is used, but no requestPermissions call or ActivityResultContracts based " +
                        "permission request was found in the code.",
                    recommendation = "Request the permission with registerForActivityResult(RequestPermission()) before the flow " +
                        "that uses it, and handle the denied case.",
                    permission = report.permission,
                    locations = report.usages
                )
            }

            if (dangerous && report.status == PermissionStatus.USED &&
                report.runtimeRequests.isNotEmpty() && report.permissionChecks.isEmpty()
            ) {
                add(
                    rule = "MISSING_PERMISSION_CHECK",
                    severity = Severity.MEDIUM,
                    title = "${report.shortName} is used without a permission state check",
                    detail = "The permission is requested, but no checkSelfPermission style guard was found before use.",
                    recommendation = "Verify the permission state with ContextCompat.checkSelfPermission before calling the API.",
                    permission = report.permission,
                    locations = report.runtimeRequests
                )
            }

            if (report.declared && report.policyNote != null) {
                add(
                    rule = "PLAY_POLICY_SENSITIVE",
                    severity = if (special) Severity.HIGH else Severity.MEDIUM,
                    title = "${report.shortName} is sensitive under Google Play policy",
                    detail = report.policyNote,
                    recommendation = "Update the Play Console declarations and privacy policy for this permission, and consider " +
                        "an alternative API that removes the need for it.",
                    permission = report.permission,
                    locations = report.declarations.map { declaration ->
                        Usage(
                            file = declaration.file,
                            line = declaration.line,
                            module = declaration.module,
                            enclosing = "AndroidManifest",
                            symbol = report.shortName,
                            kind = UsageKind.MANIFEST,
                            evidence = "Manifest declaration",
                            confidence = "HIGH",
                            snippet = declaration.snippet
                        )
                    }
                )
            }
        }

        val writeStorage = permissions.firstOrNull { it.permission == "android.permission.WRITE_EXTERNAL_STORAGE" }
        if (writeStorage != null && writeStorage.declared &&
            writeStorage.declarations.any { it.maxSdkVersion == null }
        ) {
            add(
                rule = "STORAGE_MAX_SDK",
                severity = Severity.MEDIUM,
                title = "WRITE_EXTERNAL_STORAGE has no maxSdkVersion limit",
                detail = "Because of scoped storage this permission has no effect on Android 10 and above in most cases.",
                recommendation = "Limit the declaration with android:maxSdkVersion=\"28\" or move to the MediaStore API.",
                permission = writeStorage.permission,
                locations = emptyList()
            )
        }

        val targetSdk = buildInfo.targetSdk
        val postNotifications = permissions.firstOrNull { it.permission == "android.permission.POST_NOTIFICATIONS" }
        val notificationUsage = permissions
            .firstOrNull { it.permission == "android.permission.POST_NOTIFICATIONS" }
            ?.usages
            .orEmpty()
        if (targetSdk != null && targetSdk >= 33 && notificationUsage.isNotEmpty() && postNotifications?.declared != true) {
            add(
                rule = "MISSING_POST_NOTIFICATIONS",
                severity = Severity.HIGH,
                title = "Notifications are posted without declaring POST_NOTIFICATIONS",
                detail = "With targetSdk $targetSdk, notifications are not shown on Android 13 and above without this permission.",
                recommendation = "Declare POST_NOTIFICATIONS in the manifest and request it at runtime.",
                permission = "android.permission.POST_NOTIFICATIONS",
                locations = notificationUsage
            )
        }

        val fineLocation = permissions.firstOrNull { it.permission == "android.permission.ACCESS_FINE_LOCATION" }
        val coarseLocation = permissions.firstOrNull { it.permission == "android.permission.ACCESS_COARSE_LOCATION" }
        if (fineLocation?.declared == true && coarseLocation?.declared != true) {
            add(
                rule = "FINE_WITHOUT_COARSE",
                severity = Severity.MEDIUM,
                title = "ACCESS_COARSE_LOCATION is missing next to ACCESS_FINE_LOCATION",
                detail = "On Android 12 and above the user can pick approximate location, so the coarse permission must also be declared.",
                recommendation = "Declare ACCESS_COARSE_LOCATION in the manifest and request both permissions together.",
                permission = fineLocation.permission
            )
        }

        val backgroundLocation = permissions
            .firstOrNull { it.permission == "android.permission.ACCESS_BACKGROUND_LOCATION" }
        if (backgroundLocation?.declared == true && fineLocation?.declared != true && coarseLocation?.declared != true) {
            add(
                rule = "BACKGROUND_LOCATION_WITHOUT_FOREGROUND",
                severity = Severity.HIGH,
                title = "Background location is declared without foreground location",
                detail = "ACCESS_BACKGROUND_LOCATION does not work alone, foreground location must be granted first.",
                recommendation = "Declare ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION and request the permissions in sequence.",
                permission = backgroundLocation.permission
            )
        }

        components
            .filter { it.exported == true && it.permission == null && it.hasIntentFilter }
            .forEach { component ->
                add(
                    rule = "EXPORTED_COMPONENT_WITHOUT_PERMISSION",
                    severity = Severity.MEDIUM,
                    title = "${component.name} is exported without permission protection",
                    detail = "The ${component.type} component is declared with android:exported=\"true\" and has no android:permission guard.",
                    recommendation = "Set exported to false if the component is not meant to be called externally, otherwise guard it with a permission.",
                    locations = listOf(
                        Usage(
                            file = component.file,
                            line = component.line,
                            module = component.module,
                            enclosing = "AndroidManifest",
                            symbol = component.name,
                            kind = UsageKind.MANIFEST,
                            evidence = "Exported component",
                            confidence = "HIGH",
                            snippet = ""
                        )
                    )
                )
            }

        manifests.filter { it.usesCleartextTraffic == true }.forEach { manifest ->
            add(
                rule = "CLEARTEXT_TRAFFIC",
                severity = Severity.MEDIUM,
                title = "usesCleartextTraffic is enabled",
                detail = "${manifest.relativePath} allows unencrypted HTTP traffic.",
                recommendation = "Use HTTPS only, and if an exception is required scope it per domain with a network security config."
            )
        }

        val adsDependency = buildInfo.dependencies.any { it.contains("play-services-ads") || it.contains("admob") }
        val adId = permissions.firstOrNull { it.permission == "com.google.android.gms.permission.AD_ID" }
        if (adsDependency && targetSdk != null && targetSdk >= 33 && adId?.declared != true) {
            add(
                rule = "MISSING_AD_ID",
                severity = Severity.MEDIUM,
                title = "Ads SDK is present but AD_ID is not declared",
                detail = "Ad-supported apps targeting Android 13 and above must declare com.google.android.gms.permission.AD_ID.",
                recommendation = "Declare AD_ID if the advertising id is used and update the Data Safety form."
            )
        }

        if (permissions.none { it.permission == "android.permission.INTERNET" && it.declared } &&
            permissions.any { it.permission == "android.permission.INTERNET" && it.usages.isNotEmpty() }
        ) {
            add(
                rule = "NETWORK_WITHOUT_INTERNET",
                severity = Severity.CRITICAL,
                title = "Network calls are present without the INTERNET permission",
                detail = "An HTTP client is used in the code, but the INTERNET permission is not declared.",
                recommendation = "Add the INTERNET permission to AndroidManifest.xml.",
                permission = "android.permission.INTERNET"
            )
        }

        if (manifests.isEmpty()) {
            add(
                rule = "NO_MANIFEST",
                severity = Severity.HIGH,
                title = "No AndroidManifest.xml found",
                detail = "No manifest file was found in the given directory, so permission declarations could not be analyzed.",
                recommendation = "Make sure the target path is the root directory of an Android project."
            )
        }

        if (sourceFiles.isEmpty()) {
            add(
                rule = "NO_SOURCES",
                severity = Severity.HIGH,
                title = "No source files found",
                detail = "No Kotlin, Java or Dart source file was found to analyze.",
                recommendation = "Check the target path or pass the --include-tests option."
            )
        }

        return findings
    }
}
