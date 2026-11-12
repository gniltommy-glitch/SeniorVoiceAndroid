package com.khkt.seniorvoice

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.khkt.seniorvoice.agent.ApiProvider
import com.khkt.seniorvoice.agent.ExecutionEngineMode
import com.khkt.seniorvoice.agent.ApiLlmPlanner
import com.khkt.seniorvoice.agent.SafetyGuardManager
import com.khkt.seniorvoice.context.FamilyMemoryRepository
import com.khkt.seniorvoice.executor.AndroidAppExecutor
import com.khkt.seniorvoice.speech.VietnameseSpeechManager
import com.khkt.seniorvoice.ui.BenchmarkScreen
import com.khkt.seniorvoice.ui.FirstTimeSetupScreen
import com.khkt.seniorvoice.ui.SeniorHomeScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var familyRepository: FamilyMemoryRepository
    private lateinit var appExecutor: AndroidAppExecutor
    private lateinit var speechManager: VietnameseSpeechManager
    private lateinit var llmPlanner: ApiLlmPlanner
    private lateinit var safetyGuardManager: SafetyGuardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        familyRepository = FamilyMemoryRepository(applicationContext)
        appExecutor = AndroidAppExecutor(applicationContext)
        llmPlanner = ApiLlmPlanner(familyRepository, applicationContext)

        speechManager = VietnameseSpeechManager(applicationContext) { recognizedText, isConfirmationTurn ->
            lifecycleScope.launch {
                if (isConfirmationTurn || safetyGuardManager.isAwaitingConfirmation.value) {
                    val handled = safetyGuardManager.handleVoiceConfirmationResponse(recognizedText)
                    if (handled) return@launch
                }
                val plan = llmPlanner.createPlan(recognizedText)
                safetyGuardManager.executePlanWithSafetyGuard(plan)
            }
        }

        safetyGuardManager = SafetyGuardManager(
            scope = lifecycleScope,
            appExecutor = appExecutor,
            speechManager = speechManager,
            llmPlanner = llmPlanner
        )

        setContent {
            val scope = rememberCoroutineScope()
            var isSetupDone by remember { mutableStateOf(familyRepository.isFirstSetupCompleted()) }
            var selectedTab by remember { mutableIntStateOf(0) }
            var benchmarkQueries by remember { mutableStateOf(familyRepository.loadBenchmarkQueries()) }
            var profileState by remember { mutableStateOf(familyRepository.getMemoryData().senior_profile) }

            val isListening by speechManager.isListening.collectAsState()
            val isSpeaking by speechManager.isSpeaking.collectAsState()
            val isTtsAvailable by speechManager.isTtsAvailable.collectAsState()
            val partialText by speechManager.partialText.collectAsState()
            val statusMessage by speechManager.statusMessage.collectAsState()
            val currentPlan by safetyGuardManager.currentPlan.collectAsState()
            val isAwaitingConfirm by safetyGuardManager.isAwaitingConfirmation.collectAsState()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Permissions requested on startup */ }

            LaunchedEffect(Unit) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CALL_PHONE
                    )
                )
            }

            // Nếu mới mở app lần đầu tiên -> Hiện màn hình Thiết lập 1 lần duy nhất
            if (!isSetupDone) {
                FirstTimeSetupScreen(
                    onSaveSetup = { title, fullName, address, contactNick, contactPhone, placeName, placeAddr, medName, medH, medM ->
                        familyRepository.saveInitialSetup(
                            preferredTitle = title,
                            fullName = fullName,
                            homeAddress = address,
                            contactNickname = contactNick,
                            contactPhone = contactPhone,
                            favoritePlaceName = placeName,
                            favoritePlaceAddress = placeAddr,
                            medicationName = medName,
                            medicationHour = medH,
                            medicationMinute = medM
                        )
                        profileState = familyRepository.getMemoryData().senior_profile
                        isSetupDone = true
                        Toast.makeText(
                            this@MainActivity,
                            "Đã lưu thiết lập! Chào mừng $title ${fullName.ifBlank { "" }}",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onUseSampleData = {
                        familyRepository.useSampleKhktDataAndCompleteSetup()
                        profileState = familyRepository.getMemoryData().senior_profile
                        isSetupDone = true
                        Toast.makeText(
                            this@MainActivity,
                            "Đã nạp dữ liệu mẫu KHKT!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF0B3C49),
                            contentColor = Color.White
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = {
                                    Text(
                                        "Màn Hình Chính",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF0B3C49),
                                    selectedTextColor = Color(0xFFFFD166),
                                    indicatorColor = Color(0xFFFFD166),
                                    unselectedIconColor = Color.White,
                                    unselectedTextColor = Color(0xFFCBD5E1)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = {
                                    Text(
                                        "Cập Nhật Sổ Tay",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF0B3C49),
                                    selectedTextColor = Color(0xFFFFD166),
                                    indicatorColor = Color(0xFFFFD166),
                                    unselectedIconColor = Color.White,
                                    unselectedTextColor = Color(0xFFCBD5E1)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Science, contentDescription = null) },
                                label = {
                                    Text(
                                        "Thực Nghiệm KHKT",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF0B3C49),
                                    selectedTextColor = Color(0xFFFFD166),
                                    indicatorColor = Color(0xFFFFD166),
                                    unselectedIconColor = Color.White,
                                    unselectedTextColor = Color(0xFFCBD5E1)
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> SeniorHomeScreen(
                                seniorProfile = profileState,
                                isListening = isListening,
                                isSpeaking = isSpeaking,
                                isTtsAvailable = isTtsAvailable,
                                partialSpeechText = partialText,
                                statusMessage = statusMessage,
                                currentPlan = currentPlan,
                                isAwaitingConfirmation = isAwaitingConfirm,
                                onToggleMic = {
                                    if (isListening) {
                                        speechManager.stopListening()
                                    } else {
                                        speechManager.startListening(forConfirmation = isAwaitingConfirm)
                                    }
                                },
                                onRunUtterance = { utterance ->
                                    scope.launch {
                                        val plan = llmPlanner.createPlan(utterance)
                                        safetyGuardManager.executePlanWithSafetyGuard(plan)
                                    }
                                },
                                onUpdateStepParams = { stepId, newParams, executeNow ->
                                    safetyGuardManager.updateStepParameters(stepId, newParams, executeNow)
                                },
                                onConfirmSensitive = {
                                    safetyGuardManager.confirmAndExecuteSensitiveSteps()
                                },
                                onCancelSensitive = {
                                    safetyGuardManager.cancelPendingSensitiveSteps()
                                },
                                onSpeakAgain = { textToSpeak ->
                                    speechManager.speakVietnamese(textToSpeak, autoListenForConfirmation = false)
                                }
                            )

                            1 -> UserDataAndSettingsScreen(
                                familyRepository = familyRepository,
                                planner = llmPlanner,
                                onProfileUpdated = {
                                    profileState = familyRepository.getMemoryData().senior_profile
                                },
                                onReRunInitialSetup = {
                                    familyRepository.setFirstSetupCompleted(false)
                                    isSetupDone = false
                                },
                                onToast = { msg ->
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            )

                            2 -> BenchmarkScreen(
                                queries = benchmarkQueries,
                                planner = llmPlanner,
                                onAddCustomQuery = { region, utterance, expectedTools, hasSensitive ->
                                    benchmarkQueries = familyRepository.addCustomBenchmarkQuery(
                                        region = region,
                                        utterance = utterance,
                                        expectedTools = expectedTools,
                                        hasSensitive = hasSensitive
                                    )
                                },
                                onRunQueryInLiveMode = { utterance ->
                                    selectedTab = 0
                                    scope.launch {
                                        val plan = llmPlanner.createPlan(utterance)
                                        safetyGuardManager.executePlanWithSafetyGuard(plan)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speechManager.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun UserDataAndSettingsScreen(
    familyRepository: FamilyMemoryRepository,
    planner: ApiLlmPlanner,
    onProfileUpdated: () -> Unit,
    onReRunInitialSetup: () -> Unit,
    onToast: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    var memorySnapshot by remember { mutableStateOf(familyRepository.getMemoryData()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: Người thân, 1: Địa điểm, 2: Thuốc, 3: Hồ sơ & AI

    var profileTitle by remember(refreshKey) { mutableStateOf(memorySnapshot.senior_profile.preferred_title) }
    var profileName by remember(refreshKey) { mutableStateOf(memorySnapshot.senior_profile.full_name) }
    var profileAge by remember(refreshKey) { mutableStateOf(memorySnapshot.senior_profile.age.toString()) }
    var profileAddress by remember(refreshKey) { mutableStateOf(memorySnapshot.senior_profile.home_address) }

    var contactNick by remember { mutableStateOf("") }
    var contactFullName by remember { mutableStateOf("") }
    var contactRel by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactAddress by remember { mutableStateOf("") }

    var placeNick by remember { mutableStateOf("") }
    var placeFullName by remember { mutableStateOf("") }
    var placeAddress by remember { mutableStateOf("") }
    var placeNote by remember { mutableStateOf("") }

    var medKeywords by remember { mutableStateOf("") }
    var medName by remember { mutableStateOf("") }
    var medHour by remember { mutableStateOf("7") }
    var medMinute by remember { mutableStateOf("0") }
    var medDosage by remember { mutableStateOf("") }

    val providerKeys = remember { androidx.compose.runtime.mutableStateMapOf<ApiProvider, String>().apply {
        ApiProvider.entries.forEach { put(it, planner.providerApiKey(it)) }
    } }
    var allowMetered by remember { mutableStateOf(planner.allowMeteredProviders) }
    var proxyUrl by remember { mutableStateOf(planner.apiProxyUrl) }
    var deviceToken by remember { mutableStateOf(planner.apiDeviceToken) }
    var cloudConsent by remember { mutableStateOf(planner.cloudConsent) }
    var selectedEngine by remember { mutableStateOf(planner.executionMode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFDFBF7))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Thanh nút Thiết lập lại / Nạp mẫu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onReRunInitialSetup,
                modifier = Modifier.weight(1f)
            ) {
                Text("Mở lại Thiết lập ban đầu", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    familyRepository.resetToDefaultMemory()
                    memorySnapshot = familyRepository.getMemoryData()
                    refreshKey++
                    onProfileUpdated()
                    onToast("Đã nạp bộ dữ liệu mẫu KHKT!")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Nạp mẫu KHKT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0 to "Người thân (${memorySnapshot.contacts.size})",
                1 to "Địa điểm (${memorySnapshot.places.size})",
                2 to "Thuốc (${memorySnapshot.medications.size})",
                3 to "Hồ sơ & AI"
            ).forEach { (idx, title) ->
                FilterChip(
                    selected = activeSubTab == idx,
                    onClick = { activeSubTab = idx },
                    label = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (activeSubTab) {
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.5.dp, Color(0xFF15803D))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "THÊM / CẬP NHẬT NGƯỜI THÂN:",
                            color = Color(0xFF15803D),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = contactNick,
                            onValueChange = { contactNick = it },
                            label = { Text("Cách gọi ở nhà (VD: thằng Út, con Thảo)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = contactFullName,
                            onValueChange = { contactFullName = it },
                            label = { Text("Họ và tên") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = contactPhone,
                                onValueChange = { contactPhone = it },
                                label = { Text("Số điện thoại / Zalo") },
                                modifier = Modifier.weight(1.2f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = contactRel,
                                onValueChange = { contactRel = it },
                                label = { Text("Quan hệ") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = contactAddress,
                            onValueChange = { contactAddress = it },
                            label = { Text("Địa chỉ nhà (tùy chọn)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (contactNick.isNotBlank() && contactPhone.isNotBlank()) {
                                    familyRepository.addOrUpdateContact(
                                        nicknameInput = contactNick,
                                        fullName = contactFullName.ifBlank { contactNick },
                                        relationship = contactRel,
                                        phone = contactPhone,
                                        address = contactAddress
                                    )
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                    contactNick = ""
                                    contactFullName = ""
                                    contactRel = ""
                                    contactPhone = ""
                                    contactAddress = ""
                                    onToast("Đã cập nhật người thân!")
                                } else {
                                    onToast("Vui lòng nhập Tên gọi và Số điện thoại!")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LƯU NGƯỜI THÂN", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                memorySnapshot.contacts.forEach { c ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${c.full_name} • ${c.phone}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "Tên gọi: ${c.nicknames.joinToString(", ")}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF15803D),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = {
                                    familyRepository.deleteContact(c.id)
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color(0xFFB91C1C))
                            }
                        }
                    }
                }
            }

            1 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.5.dp, Color(0xFF0369A1))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalHospital, contentDescription = null, tint = Color(0xFF0369A1))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "THÊM / CẬP NHẬT ĐỊA ĐIỂM:",
                                color = Color(0xFF0369A1),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = placeNick,
                            onValueChange = { placeNick = it },
                            label = { Text("Tên gọi tắt (VD: viện Chợ Rẫy, chùa Vĩnh Nghiêm)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = placeFullName,
                            onValueChange = { placeFullName = it },
                            label = { Text("Tên đầy đủ") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = placeAddress,
                            onValueChange = { placeAddress = it },
                            label = { Text("Địa chỉ chi tiết") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (placeNick.isNotBlank() && placeAddress.isNotBlank()) {
                                    familyRepository.addOrUpdatePlace(
                                        nicknameInput = placeNick,
                                        fullName = placeFullName.ifBlank { placeNick },
                                        address = placeAddress,
                                        reminderNote = placeNote
                                    )
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                    placeNick = ""
                                    placeFullName = ""
                                    placeAddress = ""
                                    placeNote = ""
                                    onToast("Đã cập nhật địa điểm!")
                                } else {
                                    onToast("Vui lòng nhập Tên gọi và Địa chỉ!")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LƯU ĐỊA ĐIỂM", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                memorySnapshot.places.forEach { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = p.full_name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(text = "Địa chỉ: ${p.address}", fontSize = 12.sp, color = Color(0xFF334155))
                            }
                            IconButton(
                                onClick = {
                                    familyRepository.deletePlace(p.id)
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color(0xFFB91C1C))
                            }
                        }
                    }
                }
            }

            2 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.5.dp, Color(0xFF0B3C49))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Medication, contentDescription = null, tint = Color(0xFF0B3C49))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "THÊM / CẬP NHẬT LỊCH THUỐC:",
                                color = Color(0xFF0B3C49),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = medName,
                            onValueChange = { medName = it },
                            label = { Text("Tên thuốc (VD: Thuốc huyết áp)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = medHour,
                                onValueChange = { medHour = it },
                                label = { Text("Giờ (0-23)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = medMinute,
                                onValueChange = { medMinute = it },
                                label = { Text("Phút (0-59)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = medDosage,
                            onValueChange = { medDosage = it },
                            label = { Text("Liều dùng") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (medName.isNotBlank()) {
                                    familyRepository.addOrUpdateMedication(
                                        keywordsInput = medKeywords.ifBlank { medName },
                                        medName = medName,
                                        hour = medHour.toIntOrNull() ?: 7,
                                        minute = medMinute.toIntOrNull() ?: 0,
                                        dosage = medDosage
                                    )
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                    medName = ""
                                    medKeywords = ""
                                    medDosage = ""
                                    onToast("Đã cập nhật lịch thuốc!")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B3C49))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LƯU LỊCH THUỐC", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                memorySnapshot.medications.forEach { m ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${m.name} (${String.format("%02d:%02d", m.default_hour, m.default_minute)})",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(text = "Liều dùng: ${m.dosage}", fontSize = 12.sp, color = Color(0xFF475569))
                            }
                            IconButton(
                                onClick = {
                                    familyRepository.deleteMedication(m.id)
                                    memorySnapshot = familyRepository.getMemoryData()
                                    refreshKey++
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color(0xFFB91C1C))
                            }
                        }
                    }
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.5.dp, Color(0xFF0B3C49))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF0B3C49))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CẬP NHẬT HỒ SƠ CÁ NHÂN:",
                                color = Color(0xFF0B3C49),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = profileTitle,
                                onValueChange = { profileTitle = it },
                                label = { Text("Cách xưng hô (Bác, Ông, Bà...)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = profileAge,
                                onValueChange = { profileAge = it },
                                label = { Text("Tuổi") },
                                modifier = Modifier.weight(0.7f),
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Họ và tên") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = profileAddress,
                            onValueChange = { profileAddress = it },
                            label = { Text("Địa chỉ / Thành phố") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                familyRepository.updateSeniorProfile(
                                    fullName = profileName,
                                    preferredTitle = profileTitle,
                                    age = profileAge.toIntOrNull() ?: 70,
                                    homeAddress = profileAddress,
                                    medicalNotes = ""
                                )
                                memorySnapshot = familyRepository.getMemoryData()
                                onProfileUpdated()
                                onToast("Đã cập nhật Hồ sơ!")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B3C49))
                        ) {
                            Text("LƯU HỒ SƠ", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.5.dp, Color(0xFF0B3C49))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SettingsEthernet, contentDescription = null, tint = Color(0xFF0B3C49))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CẤU HÌNH XỬ LÝ GIỌNG NÓI",
                                color = Color(0xFF0B3C49),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        listOf(
                            ExecutionEngineMode.API_DIRECT to "Gọi API trực tiếp bằng key trên điện thoại",
                            ExecutionEngineMode.API_PROXY to "Dùng gateway API của nhóm (tùy chọn)"
                        ).forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedEngine == mode,
                                    onClick = {
                                        selectedEngine = mode
                                        planner.executionMode = mode
                                    }
                                )
                                Text(text = label, fontSize = 13.sp)
                            }
                        }

                        if (selectedEngine == ExecutionEngineMode.API_DIRECT) {
                            ApiProvider.entries.forEach { provider ->
                                OutlinedTextField(
                                    value = providerKeys[provider].orEmpty(),
                                    onValueChange = { providerKeys[provider] = it },
                                    label = { Text("${provider.label} API key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Text(provider.model, fontSize = 11.sp)
                            }
                            Text("Nhập ít nhất một key. Thứ tự: Qwen → OpenRouter miễn phí → Groq → Gemini → DeepSeek.", fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = allowMetered, onCheckedChange = { allowMetered = it })
                                Text("Cho phép Gemini/DeepSeek dự phòng (có thể tính phí)", fontSize = 12.sp)
                            }
                        } else {
                            OutlinedTextField(
                                value = proxyUrl, onValueChange = { proxyUrl = it },
                                label = { Text("Địa chỉ gateway API (https://...)") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                value = deviceToken, onValueChange = { deviceToken = it },
                                label = { Text("Mã thiết bị do người quản lý cấp") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = cloudConsent, onCheckedChange = { cloudConsent = it })
                            Text("Cho phép gửi câu nói đã thay tên/số điện thoại tới các dịch vụ AI đã cấu hình khi cần. Nội dung vẫn có thể chứa thông tin riêng tư.", fontSize = 12.sp)
                        }
                        Text("Key nhập trên điện thoại được mã hóa bằng Android Keystore. Có thể xóa key hoặc tắt quyền gửi bất kỳ lúc nào.", fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (selectedEngine == ExecutionEngineMode.API_PROXY && proxyUrl.isNotBlank() && !proxyUrl.trim().startsWith("https://")) {
                                    onToast("Địa chỉ proxy phải dùng HTTPS")
                                    return@Button
                                }
                                planner.apiProxyUrl = proxyUrl
                                planner.apiDeviceToken = deviceToken
                                planner.cloudConsent = cloudConsent
                                val saved = runCatching {
                                    ApiProvider.entries.forEach { planner.saveProviderApiKey(it, providerKeys[it].orEmpty()) }
                                }
                                if (saved.isFailure) {
                                    onToast("Không lưu được key API. Bác thử lại giúp con nhé.")
                                    return@Button
                                }
                                planner.allowMeteredProviders = allowMetered
                                planner.executionMode = selectedEngine
                                onToast("Đã lưu cấu hình AI!")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B3C49))
                        ) {
                            Text("LƯU CẤU HÌNH AI", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
