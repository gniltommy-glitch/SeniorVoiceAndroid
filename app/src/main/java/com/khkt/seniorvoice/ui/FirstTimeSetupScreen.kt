package com.khkt.seniorvoice.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SetupTeal = Color(0xFF0B3C49)
private val SetupBg = Color(0xFFFDFBF7)
private val SetupGreen = Color(0xFF15803D)

/**
 * Màn hình Thiết Lập Lần Đầu (Chỉ hiện 1 lần duy nhất khi mới cài app)
 * Giao diện tối giản, chữ to, dễ điền cho người cao tuổi hoặc người thân thiết lập hộ.
 */
@Composable
fun FirstTimeSetupScreen(
    onSaveSetup: (
        preferredTitle: String,
        fullName: String,
        homeAddress: String,
        contactNickname: String,
        contactPhone: String,
        favoritePlaceName: String,
        favoritePlaceAddress: String,
        medicationName: String,
        medicationHour: Int,
        medicationMinute: Int
    ) -> Unit,
    onUseSampleData: () -> Unit
) {
    val scrollState = rememberScrollState()

    var title by remember { mutableStateOf("Bác") }
    var fullName by remember { mutableStateOf("") }
    var cityOrAddress by remember { mutableStateOf("TP. Hồ Chí Minh") }

    var contactNick by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }

    var placeName by remember { mutableStateOf("") }
    var placeAddr by remember { mutableStateOf("") }

    var medName by remember { mutableStateOf("") }
    var medHour by remember { mutableStateOf("7") }
    var medMin by remember { mutableStateOf("00") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SetupBg)
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "THIẾT LẬP BAN ĐẦU",
            color = SetupTeal,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Chỉ cần nhập 1 lần đầu tiên khi mới cài ứng dụng. Bạn có thể cập nhật lại bất cứ lúc nào ở tab Cập Nhật Sổ Tay.",
            color = Color(0xFF475569),
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Bước 1: Thông tin người dùng
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, Color(0xFFCBD5E1))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. Thông tin của bạn",
                    color = SetupTeal,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Bác", "Ông", "Bà", "Cô", "Chú").forEach { item ->
                        FilterChip(
                            selected = title == item,
                            onClick = { title = item },
                            label = { Text(item, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("Tên của bạn (VD: Người dùng minh họa)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = cityOrAddress,
                    onValueChange = { cityOrAddress = it },
                    label = { Text("Tỉnh / Thành phố hoặc Địa chỉ nhà") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bước 2: Người thân liên lạc chính
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, Color(0xFFCBD5E1))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "2. Người thân hay gọi / nhắn tin (Tùy chọn)",
                    color = SetupTeal,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = contactNick,
                    onValueChange = { contactNick = it },
                    label = { Text("Tên thường gọi ở nhà (VD: thằng Út, con Thảo)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it },
                    label = { Text("Số điện thoại / Zalo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bước 3: Địa điểm & Thuốc hàng ngày
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, Color(0xFFCBD5E1))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "3. Nơi hay đi & Lịch thuốc (Tùy chọn)",
                    color = SetupTeal,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    label = { Text("Tên gọi nơi hay đi (VD: viện Chợ Rẫy, chùa Vĩnh Nghiêm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = placeAddr,
                    onValueChange = { placeAddr = it },
                    label = { Text("Địa chỉ nơi đó (để mở Bản đồ / Đặt xe)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = medName,
                        onValueChange = { medName = it },
                        label = { Text("Thuốc hàng ngày (VD: thuốc huyết áp)") },
                        modifier = Modifier.weight(1.4f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = medHour,
                        onValueChange = { medHour = it },
                        label = { Text("Giờ") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = medMin,
                        onValueChange = { medMin = it },
                        label = { Text("Phút") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                onSaveSetup(
                    title,
                    fullName,
                    cityOrAddress,
                    contactNick,
                    contactPhone,
                    placeName,
                    placeAddr,
                    medName,
                    medHour.toIntOrNull() ?: 7,
                    medMin.toIntOrNull() ?: 0
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SetupGreen)
        ) {
            Text(
                text = "LƯU & VÀO MÀN HÌNH CHÍNH",
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onUseSampleData,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Dùng sẵn dữ liệu mẫu KHKT (Bác Ba)",
                color = SetupTeal,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
