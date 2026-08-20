package dev.apum.catalog

import dev.apum.model.Confidence

data class ApiSignal(
    val label: String,
    val regex: Regex,
    val requiresAnyImport: List<String> = emptyList(),
    val confidence: Confidence = Confidence.HIGH
)

data class PermissionSpec(
    val name: String,
    val protection: String,
    val group: String,
    val description: String,
    val policyNote: String? = null,
    val signals: List<ApiSignal> = emptyList()
) {
    val shortName: String get() = name.substringAfterLast('.')
}

object Protection {
    const val DANGEROUS = "DANGEROUS"
    const val NORMAL = "NORMAL"
    const val SPECIAL = "SPECIAL"
    const val SIGNATURE = "SIGNATURE"
}

private fun sig(
    label: String,
    pattern: String,
    imports: List<String> = emptyList(),
    confidence: Confidence = Confidence.HIGH
) = ApiSignal(label, Regex(pattern), imports, confidence)

object PermissionCatalog {

    val specs: List<PermissionSpec> = listOf(
        PermissionSpec(
            name = "android.permission.CAMERA",
            protection = Protection.DANGEROUS,
            group = "Camera",
            description = "Access to the device camera for photo and video capture.",
            policyNote = "Play Console requires a camera usage justification and a privacy policy.",
            signals = listOf(
                sig("CameraX ProcessCameraProvider", """ProcessCameraProvider\s*\.\s*getInstance|bindToLifecycle\s*\("""),
                sig("Camera2 openCamera", """\bopenCamera\s*\(|\bCameraManager\b"""),
                sig("Deprecated Camera API", """\bCamera\s*\.\s*open\s*\("""),
                sig("MediaRecorder video source", """setVideoSource\s*\("""),
                sig("Camera intent", """ACTION_IMAGE_CAPTURE|ACTION_VIDEO_CAPTURE""", confidence = Confidence.MEDIUM),
                sig("CameraX use cases", """\bImageCapture\s*\.|\bImageAnalysis\s*\.|CameraSelector\s*\."""),
                sig("Camera permission constant", """Manifest\.permission\.CAMERA""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.RECORD_AUDIO",
            protection = Protection.DANGEROUS,
            group = "Microphone",
            description = "Records audio from the microphone.",
            signals = listOf(
                sig("AudioRecord", """\bAudioRecord\s*[\(\.]"""),
                sig("MediaRecorder audio source", """setAudioSource\s*\("""),
                sig("SpeechRecognizer", """SpeechRecognizer\s*\.|RecognizerIntent\s*\."""),
                sig("AudioPlaybackCapture", """AudioPlaybackCaptureConfiguration""", confidence = Confidence.MEDIUM),
                sig("Visualizer", """\bVisualizer\s*\(""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.ACCESS_FINE_LOCATION",
            protection = Protection.DANGEROUS,
            group = "Location",
            description = "Access to GPS-accurate location data.",
            policyNote = "Location collection must be declared in the Play Data Safety form.",
            signals = listOf(
                sig("FusedLocationProviderClient", """FusedLocationProviderClient|getFusedLocationProviderClient"""),
                sig("LocationManager updates", """requestLocationUpdates\s*\(|getLastKnownLocation\s*\("""),
                sig("LocationRequest", """LocationRequest\s*\.|PRIORITY_HIGH_ACCURACY"""),
                sig("GNSS status", """registerGnssStatusCallback\s*\(|addNmeaListener\s*\("""),
                sig("Cell info", """getAllCellInfo\s*\(""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.ACCESS_COARSE_LOCATION",
            protection = Protection.DANGEROUS,
            group = "Location",
            description = "Access to approximate location.",
            signals = listOf(
                sig("Fused location", """FusedLocationProviderClient|getFusedLocationProviderClient"""),
                sig("Coarse priority", """PRIORITY_BALANCED_POWER_ACCURACY|PRIORITY_LOW_POWER"""),
                sig("Network provider", """NETWORK_PROVIDER"""),
                sig("Wifi scan results", """getScanResults\s*\(""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.ACCESS_BACKGROUND_LOCATION",
            protection = Protection.DANGEROUS,
            group = "Location",
            description = "Location access while the app runs in the background.",
            policyNote = "Requires a separate Google Play review and a demo video.",
            signals = listOf(
                sig("Geofencing", """GeofencingClient|GeofencingRequest|addGeofences\s*\("""),
                sig("Location foreground service", """FOREGROUND_SERVICE_TYPE_LOCATION"""),
                sig("Background location updates", """requestLocationUpdates\s*\(""", confidence = Confidence.LOW)
            )
        ),
        PermissionSpec(
            name = "android.permission.READ_CONTACTS",
            protection = Protection.DANGEROUS,
            group = "Contacts",
            description = "Reads contact records.",
            signals = listOf(
                sig("ContactsContract", """ContactsContract\s*\."""),
                sig("Contacts content uri", """content://com\.android\.contacts""")
            )
        ),
        PermissionSpec(
            name = "android.permission.WRITE_CONTACTS",
            protection = Protection.DANGEROUS,
            group = "Contacts",
            description = "Creates or modifies contact records.",
            signals = listOf(
                sig("Contacts write", """applyBatch\s*\(|RawContacts\s*\.\s*CONTENT_URI""")
            )
        ),
        PermissionSpec(
            name = "android.permission.GET_ACCOUNTS",
            protection = Protection.DANGEROUS,
            group = "Contacts",
            description = "Access to the list of accounts on the device.",
            signals = listOf(sig("AccountManager", """AccountManager\s*\.|getAccountsByType\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.READ_CALENDAR",
            protection = Protection.DANGEROUS,
            group = "Calendar",
            description = "Reads calendar events.",
            signals = listOf(sig("CalendarContract", """CalendarContract\s*\."""))
        ),
        PermissionSpec(
            name = "android.permission.WRITE_CALENDAR",
            protection = Protection.DANGEROUS,
            group = "Calendar",
            description = "Creates or updates calendar events.",
            signals = listOf(sig("Calendar write", """Events\s*\.\s*CONTENT_URI"""))
        ),
        PermissionSpec(
            name = "android.permission.READ_SMS",
            protection = Protection.DANGEROUS,
            group = "SMS",
            description = "Reads SMS messages.",
            policyNote = "Requires a special declaration under the Play SMS and Call Log policy.",
            signals = listOf(sig("SMS provider", """Telephony\s*\.\s*Sms|content://sms"""))
        ),
        PermissionSpec(
            name = "android.permission.SEND_SMS",
            protection = Protection.DANGEROUS,
            group = "SMS",
            description = "Sends SMS messages from the app.",
            policyNote = "Requires a special declaration under the Play SMS policy.",
            signals = listOf(sig("SmsManager", """SmsManager\s*\.|sendTextMessage\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.RECEIVE_SMS",
            protection = Protection.DANGEROUS,
            group = "SMS",
            description = "Intercepts incoming SMS messages.",
            policyNote = "Requires a special declaration under the Play SMS policy.",
            signals = listOf(sig("SMS receiver", """SMS_RECEIVED"""))
        ),
        PermissionSpec(
            name = "android.permission.CALL_PHONE",
            protection = Protection.DANGEROUS,
            group = "Phone",
            description = "Places a phone call directly.",
            signals = listOf(sig("ACTION_CALL", """ACTION_CALL\b|placeCall\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.READ_PHONE_STATE",
            protection = Protection.DANGEROUS,
            group = "Phone",
            description = "Access to phone state and network information.",
            signals = listOf(
                sig("TelephonyManager", """TelephonyManager\s*\.|getDeviceId\s*\(|getSubscriberId\s*\("""),
                sig("Phone state listener", """PhoneStateListener|TelephonyCallback""")
            )
        ),
        PermissionSpec(
            name = "android.permission.READ_CALL_LOG",
            protection = Protection.DANGEROUS,
            group = "Phone",
            description = "Reads the call history.",
            policyNote = "Requires a special declaration under the Play Call Log policy.",
            signals = listOf(sig("CallLog provider", """CallLog\s*\.|content://call_log"""))
        ),
        PermissionSpec(
            name = "android.permission.READ_EXTERNAL_STORAGE",
            protection = Protection.DANGEROUS,
            group = "Storage",
            description = "Reads files from shared external storage.",
            signals = listOf(
                sig("MediaStore query", """MediaStore\s*\.\s*(Images|Video|Audio|Files)"""),
                sig("External storage path", """getExternalStorageDirectory\s*\(|Environment\s*\.\s*DIRECTORY_"""),
                sig("File input stream", """FileInputStream\s*\(""", confidence = Confidence.LOW)
            )
        ),
        PermissionSpec(
            name = "android.permission.WRITE_EXTERNAL_STORAGE",
            protection = Protection.DANGEROUS,
            group = "Storage",
            description = "Writes files to shared external storage.",
            signals = listOf(
                sig("MediaStore insert", """insert\s*\(\s*MediaStore"""),
                sig("Public directory write", """getExternalStoragePublicDirectory\s*\("""),
                sig("File output stream", """FileOutputStream\s*\(""", confidence = Confidence.LOW)
            )
        ),
        PermissionSpec(
            name = "android.permission.MANAGE_EXTERNAL_STORAGE",
            protection = Protection.SPECIAL,
            group = "Storage",
            description = "Broad access to the entire file system.",
            policyNote = "Requires a special Google Play declaration form and is rejected for most apps.",
            signals = listOf(sig("All files access", """MANAGE_ALL_FILES_ACCESS_PERMISSION|isExternalStorageManager\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.READ_MEDIA_IMAGES",
            protection = Protection.DANGEROUS,
            group = "Media",
            description = "Access to image media on Android 13 and above.",
            signals = listOf(sig("MediaStore images", """MediaStore\s*\.\s*Images"""))
        ),
        PermissionSpec(
            name = "android.permission.READ_MEDIA_VIDEO",
            protection = Protection.DANGEROUS,
            group = "Media",
            description = "Access to video media on Android 13 and above.",
            signals = listOf(sig("MediaStore video", """MediaStore\s*\.\s*Video"""))
        ),
        PermissionSpec(
            name = "android.permission.READ_MEDIA_AUDIO",
            protection = Protection.DANGEROUS,
            group = "Media",
            description = "Access to audio files on Android 13 and above.",
            signals = listOf(sig("MediaStore audio", """MediaStore\s*\.\s*Audio"""))
        ),
        PermissionSpec(
            name = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            protection = Protection.DANGEROUS,
            group = "Media",
            description = "Partial photo and video selection access on Android 14.",
            signals = listOf(sig("Photo picker", """ACTION_PICK_IMAGES|PickVisualMedia"""))
        ),
        PermissionSpec(
            name = "android.permission.POST_NOTIFICATIONS",
            protection = Protection.DANGEROUS,
            group = "Notification",
            description = "Posts notifications on Android 13 and above.",
            signals = listOf(
                sig("NotificationManagerCompat", """NotificationManagerCompat"""),
                sig("Notification builder", """NotificationCompat\s*\.\s*Builder"""),
                sig("Notify call", """\.notify\s*\(""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.BLUETOOTH_SCAN",
            protection = Protection.DANGEROUS,
            group = "Bluetooth",
            description = "Scans for nearby Bluetooth devices.",
            signals = listOf(sig("BLE scan", """startScan\s*\(|BluetoothLeScanner|startDiscovery\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.BLUETOOTH_CONNECT",
            protection = Protection.DANGEROUS,
            group = "Bluetooth",
            description = "Connects to paired Bluetooth devices.",
            signals = listOf(sig("Bluetooth connect", """connectGatt\s*\(|createRfcommSocket|getBondedDevices\s*\(|bondedDevices"""))
        ),
        PermissionSpec(
            name = "android.permission.BLUETOOTH_ADVERTISE",
            protection = Protection.DANGEROUS,
            group = "Bluetooth",
            description = "Makes the device discoverable over Bluetooth.",
            signals = listOf(sig("BLE advertise", """BluetoothLeAdvertiser|startAdvertising\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.NEARBY_WIFI_DEVICES",
            protection = Protection.DANGEROUS,
            group = "Network",
            description = "Access to nearby Wi-Fi devices.",
            signals = listOf(sig("Wifi direct", """WifiP2pManager|WifiAwareManager|WifiRttManager"""))
        ),
        PermissionSpec(
            name = "android.permission.BODY_SENSORS",
            protection = Protection.DANGEROUS,
            group = "Sensors",
            description = "Access to body sensor data such as heart rate.",
            signals = listOf(sig("Heart rate sensor", """TYPE_HEART_RATE|TYPE_HEART_BEAT"""))
        ),
        PermissionSpec(
            name = "android.permission.ACTIVITY_RECOGNITION",
            protection = Protection.DANGEROUS,
            group = "Sensors",
            description = "Step counting and physical activity recognition.",
            signals = listOf(
                sig("Step sensors", """TYPE_STEP_COUNTER|TYPE_STEP_DETECTOR"""),
                sig("Activity recognition", """ActivityRecognition\s*\.|ActivityRecognitionClient""")
            )
        ),
        PermissionSpec(
            name = "android.permission.INTERNET",
            protection = Protection.NORMAL,
            group = "Network",
            description = "Sends and receives data over the network.",
            signals = listOf(
                sig("OkHttp", """OkHttpClient"""),
                sig("Retrofit", """Retrofit\s*\.\s*Builder"""),
                sig("Ktor client", """(?<![A-Za-z])HttpClient\s*\("""),
                sig("HttpURLConnection", """HttpURLConnection"""),
                sig("Firebase", """FirebaseFirestore|FirebaseAuth|FirebaseDatabase""", confidence = Confidence.MEDIUM),
                sig("WebView", """loadUrl\s*\(""", confidence = Confidence.MEDIUM),
                sig("Image loading", """Glide\s*\.|AsyncImage\s*\(|rememberAsyncImagePainter""", confidence = Confidence.MEDIUM)
            )
        ),
        PermissionSpec(
            name = "android.permission.ACCESS_NETWORK_STATE",
            protection = Protection.NORMAL,
            group = "Network",
            description = "Queries network connectivity state.",
            signals = listOf(sig("ConnectivityManager", """ConnectivityManager|NetworkCapabilities|activeNetworkInfo"""))
        ),
        PermissionSpec(
            name = "android.permission.ACCESS_WIFI_STATE",
            protection = Protection.NORMAL,
            group = "Network",
            description = "Queries Wi-Fi state information.",
            signals = listOf(sig("WifiManager", """WifiManager|connectionInfo"""))
        ),
        PermissionSpec(
            name = "android.permission.CHANGE_WIFI_STATE",
            protection = Protection.NORMAL,
            group = "Network",
            description = "Changes the Wi-Fi connection state.",
            signals = listOf(sig("Wifi change", """setWifiEnabled\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.VIBRATE",
            protection = Protection.NORMAL,
            group = "Device",
            description = "Uses the vibration motor.",
            signals = listOf(sig("Vibrator", """\bVibrator\b|VibrationEffect|performHapticFeedback\s*\(|HapticFeedback"""))
        ),
        PermissionSpec(
            name = "android.permission.WAKE_LOCK",
            protection = Protection.NORMAL,
            group = "Device",
            description = "Keeps the device awake.",
            signals = listOf(
                sig("WakeLock", """newWakeLock\s*\(|PowerManager\s*\.\s*WakeLock"""),
                sig("Firebase messaging", """FirebaseMessagingService""", confidence = Confidence.LOW)
            )
        ),
        PermissionSpec(
            name = "android.permission.FOREGROUND_SERVICE",
            protection = Protection.NORMAL,
            group = "Service",
            description = "Starts a foreground service.",
            signals = listOf(sig("startForeground", """startForeground\s*\(|startForegroundService\s*\(|setForeground\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.FOREGROUND_SERVICE_LOCATION",
            protection = Protection.NORMAL,
            group = "Service",
            description = "Foreground service of type location.",
            signals = listOf(sig("Location service type", """FOREGROUND_SERVICE_TYPE_LOCATION"""))
        ),
        PermissionSpec(
            name = "android.permission.RECEIVE_BOOT_COMPLETED",
            protection = Protection.NORMAL,
            group = "Service",
            description = "Receives a broadcast when the device finishes booting.",
            signals = listOf(sig("Boot receiver", """BOOT_COMPLETED"""))
        ),
        PermissionSpec(
            name = "android.permission.SCHEDULE_EXACT_ALARM",
            protection = Protection.SPECIAL,
            group = "Alarm",
            description = "Schedules exact alarms.",
            policyNote = "Play requires justification for exact alarm access.",
            signals = listOf(sig("Exact alarm", """setExact\s*\(|setExactAndAllowWhileIdle\s*\(|setAlarmClock\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.USE_EXACT_ALARM",
            protection = Protection.SPECIAL,
            group = "Alarm",
            description = "Exact alarm access for alarm and calendar apps.",
            policyNote = "Accepted only for apps whose core function is alarms or calendars.",
            signals = listOf(sig("Exact alarm", """setExactAndAllowWhileIdle\s*\(|setAlarmClock\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.QUERY_ALL_PACKAGES",
            protection = Protection.SPECIAL,
            group = "Packages",
            description = "Lists every app installed on the device.",
            policyNote = "High risk permission; in most cases replace it with a queries block.",
            signals = listOf(sig("Package query", """getInstalledPackages\s*\(|getInstalledApplications\s*\(|queryIntentActivities\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.SYSTEM_ALERT_WINDOW",
            protection = Protection.SPECIAL,
            group = "Display",
            description = "Draws over other apps.",
            policyNote = "Frequently reviewed by Google Play.",
            signals = listOf(sig("Overlay", """TYPE_APPLICATION_OVERLAY|canDrawOverlays\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.REQUEST_INSTALL_PACKAGES",
            protection = Protection.SPECIAL,
            group = "Packages",
            description = "Requests installation of other packages.",
            policyNote = "Should be removed from apps that do not distribute APKs.",
            signals = listOf(sig("Install packages", """ACTION_INSTALL_PACKAGE|canRequestPackageInstalls\s*\("""))
        ),
        PermissionSpec(
            name = "android.permission.USE_BIOMETRIC",
            protection = Protection.NORMAL,
            group = "Security",
            description = "Uses biometric authentication.",
            signals = listOf(sig("BiometricPrompt", """BiometricPrompt|BiometricManager"""))
        ),
        PermissionSpec(
            name = "android.permission.NFC",
            protection = Protection.NORMAL,
            group = "Device",
            description = "Uses the NFC hardware.",
            signals = listOf(sig("NfcAdapter", """NfcAdapter|IsoDep|NdefMessage"""))
        ),
        PermissionSpec(
            name = "android.permission.MODIFY_AUDIO_SETTINGS",
            protection = Protection.NORMAL,
            group = "Audio",
            description = "Changes global audio settings.",
            signals = listOf(sig("AudioManager", """requestAudioFocus\s*\(|setSpeakerphoneOn\s*\("""))
        ),
        PermissionSpec(
            name = "com.android.vending.BILLING",
            protection = Protection.NORMAL,
            group = "Billing",
            description = "Google Play in-app purchases.",
            signals = listOf(sig("BillingClient", """BillingClient|queryProductDetails|acknowledgePurchase"""))
        ),
        PermissionSpec(
            name = "com.google.android.gms.permission.AD_ID",
            protection = Protection.NORMAL,
            group = "Ads",
            description = "Access to the advertising identifier.",
            policyNote = "Must be declared by ad-supported apps targeting Android 13 and above.",
            signals = listOf(sig("Ads SDK", """MobileAds\s*\.|AdRequest|AdView|InterstitialAd|RewardedAd|AdvertisingIdClient"""))
        )
    )

    private val byName: Map<String, PermissionSpec> = specs.associateBy { it.name }

    fun find(name: String): PermissionSpec? = byName[name]

    fun describe(name: String): PermissionSpec = byName[name] ?: PermissionSpec(
        name = name,
        protection = if (name.startsWith("android.permission.")) Protection.NORMAL else Protection.SIGNATURE,
        group = "Other",
        description = "Permission is not in the catalog and needs manual verification."
    )
}
