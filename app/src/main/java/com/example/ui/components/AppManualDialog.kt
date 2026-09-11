package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R

enum class ManualSection(val title: String, val icon: ImageVector) {
    OVERVIEW("معرفی کلی", Icons.Default.HelpOutline),
    HOST("راهنمای میزبان", Icons.Default.WifiTethering),
    SECURITY("تایید اتصالات", Icons.Default.Security),
    SPEAKER("راهنمای مستمعین", Icons.Default.Speaker),
    STORAGE("فایل‌ها و نواها", Icons.Default.FolderOpen),
    TROUBLESHOOTING("رفع اشکالات", Icons.Default.Speed)
}

data class GuideItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val tip: String? = null
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppManualDialog(
    onDismiss: () -> Unit
) {
    var currentSection by remember { mutableStateOf(ManualSection.OVERVIEW) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("app_manual_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "راهنمای جامع هم‌صدا",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "سیستم همگام‌سازی صوت در مساجد و مراسم مذهبی",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_manual_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "بستن",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section Chips / Tabs
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ManualSection.values().forEach { section ->
                        val isSelected = currentSection == section
                        FilterChip(
                            selected = isSelected,
                            onClick = { currentSection = section },
                            label = { Text(text = section.title, fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = section.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Content Section
                AnimatedContent(
                    targetState = currentSection,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "manual_content_switch",
                    modifier = Modifier.weight(1f)
                ) { section ->
                    ManualSectionContent(section = section)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Close Action
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = "متوجه شدم و بازگشت به برنامه", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ManualSectionContent(section: ManualSection) {
    val items = when (section) {
        ManualSection.OVERVIEW -> listOf(
            GuideItem(
                title = "هم‌صدا چیست و چگونه کار می‌کند؟",
                description = "«هم‌صدا» یک سامانه پخش و همگام‌سازی صوت در مقیاس شبکه محلی (LAN) است که گوشی‌های هوشمند مستمعین را در گوشه و کنار مساجد، حسینیه‌ها و تکایا به بلندگوهای هماهنگ تبدیل می‌کند.",
                icon = Icons.Default.Speaker,
                tip = "بدون نیاز به اینترنت: کل تبادل صدا و فرامین روی مودم وای‌فای یا نقطه اتصال (Hotspot) محلی انجام می‌گیرد."
            ),
            GuideItem(
                title = "حل مشکل نقاط کور صوتی در مساجد",
                description = "در بسیاری از شبستان‌ها، بالکن‌ها و حیاط مساجد صدای مداحی و منبر با اکو یا وضوح پایین می‌رسد. با هم‌صدا، هر فرد می‌تواند با هندزفری یا اسپیکر همراه خود، نوای زنده را با شفافیت استودیویی بشنود.",
                icon = Icons.Default.Speed,
                tip = "همگام‌سازی میلی‌ثانیه‌ای (NTP Clock Sync) تضمین می‌کند هیچ تاخیر یا اکوی نامطلوبی بین گوشی‌ها ایجاد نشود."
            )
        )

        ManualSection.HOST -> listOf(
            GuideItem(
                title = "۱. آماده‌سازی شبکه برای شروع پخش",
                description = "دستگاه میزبان (گوشی مداح یا مسئول صوت) باید حالت «میزبان هم‌صدا» را فعال کند. سپس وای‌فای یا هات‌اسپات گوشی را روشن کنید تا سایرین بتوانند متصل شوند.",
                icon = Icons.Default.WifiTethering,
                tip = "اگر مودم در مسجد ندارید، کافیست هات‌اسپات (Hotspot) یکی از گوشی‌ها را روشن کنید و بقیه به آن وصل شوند."
            ),
            GuideItem(
                title = "۲. پخش زنده صدای مداح و سخنران (Live Mic)",
                description = "با لمس دکمه «میکروفن زنده»، صدای محیط یا میکروفن متصل به گوشی میزبان بلافاصله به تمام بلندگوها استریم می‌شود و صدای موسیقی پس‌زمینه به صورت خودکار ملایم (Duck) می‌گردد.",
                icon = Icons.Default.Mic,
                tip = "برای کیفیت بهتر می‌توانید از میکروفن‌های یقه‌ای بی‌سیم یا با سیم متصل به جک/تایپ‌سی استفاده کنید."
            ),
            GuideItem(
                title = "۳. بهینه‌ساز باتری و ترافیک شبکه",
                description = "با فعال کردن «حالت ذخیره نیرو»، نرخ تبادل داده در شبکه کاهش یافته و مصرف باتری در مراسم طولانی چند ساعته تا ۴۰٪ بهینه‌تر خواهد شد.",
                icon = Icons.Default.BatteryChargingFull
            )
        )

        ManualSection.SECURITY -> listOf(
            GuideItem(
                title = "مدیریت کاربران و تایید اتصال (Host Approval)",
                description = "جهت حفظ نظم در مجلس و جلوگیری از اتصال ناخواسته یا افراد متفرقه، نرم‌افزار مجهز به سیستم تایید دسترسی است. هر فردی که دکمه اتصال را بزند، ابتدا در وضعیت «در انتظار تایید» قرار می‌گیرد.",
                icon = Icons.Default.Security,
                tip = "میزبان در بخش بالای صفحه، نام دستگاه متقاضی را مشاهده کرده و می‌تواند آن را «تایید»، «رد» یا «مسدود» کند."
            ),
            GuideItem(
                title = "کلید تایید خودکار یا دستی",
                description = "میزبان می‌تواند با فعال یا غیرفعال کردن گزینه «تایید دستی اتصالات»، تعیین کند که همه افراد به طور مستقیم متصل شوند یا هر اتصال نیازمند مجوز باشد.",
                icon = Icons.Default.People
            ),
            GuideItem(
                title = "قطع اتصال و مسدودسازی دستگاه‌های خاطی",
                description = "در هر لحظه می‌توانید یک بلندگو را از لیست بلندگوهای متصل قطع کنید یا با زدن دکمه مسدودسازی، مانع از درخواست مجدد آن شوید.",
                icon = Icons.Default.Close,
                tip = "لیست دستگاه‌های مسدود شده در پایین صفحه میزبان قابل مشاهده و رفع مسدودیت است."
            )
        )

        ManualSection.SPEAKER -> listOf(
            GuideItem(
                title = "۱. اتصال در نقش مستمع یا بلندگوی کمکی",
                description = "برنامه را در حالت «بلندگوی همراه» قرار دهید. برنامه به کمک پروتکل کشف خودکار (NSD)، میزبان فعال در مسجد را روی صفحه نمایش می‌دهد. کافیست دکمه «اتصال به این میزبان» را لمس کنید.",
                icon = Icons.Default.Speaker,
                tip = "پس از زدن دکمه اتصال، پیامی مبنی بر «در انتظار تایید میزبان» مشاهده می‌کنید. پس از تایید توسط میزبان، صدا آغاز خواهد شد."
            ),
            GuideItem(
                title = "۲. کالیبراسیون تاخیر زمانی (Latency Calibrator)",
                description = "به دلیل فاصله فیزیکی شما از بلندگوهای اصلی مسجد یا تفاوت بلوتوث هندزفری، می‌توانید با اسلایدر تاخیر دستی (میلی‌ثانیه)، زمان پخش را دقیقاً با صدای محیطی مسجد منطبق کنید.",
                icon = Icons.Default.Speed,
                tip = "اگر از هندزفری بلوتوثی استفاده می‌کنید، اسلایدر را حدود +۱۵۰ تا +۲۵۰ میلی‌ثانیه قرار دهید تا تاخیر بلوتوث جبران شود."
            )
        )

        ManualSection.STORAGE -> listOf(
            GuideItem(
                title = "مدیریت فایل‌های صوتی حافظه داخلی گوشی",
                description = "می‌توانید تمام فایل‌های مداحی، دعا، قرآن و سخنرانی‌های ذخیره شده در حافظه داخلی گوشی را مستقیماً از بخش «پلی‌لیست مشترک > مدیریت فایل حافظه» جستجو کرده و به لیست پخش اضافه نمایید.",
                icon = Icons.Default.FolderOpen,
                tip = "فیلتر بر اساس پوشه‌های حافظه (مانند Telegram، Download، Music) کار با حجم زیادی از فایل‌ها را آسان می‌کند."
            ),
            GuideItem(
                title = "سیستم رای‌دهی (Upvote) و درخواست قطعه",
                description = "مستمعین متصل می‌توانند به نوحه‌ها و قطعات موجود در پلی‌لیست رای دهند تا محبوب‌ترین قطعه‌ها برای پخش بعدی اولویت پیدا کنند.",
                icon = Icons.Default.QueueMusic
            )
        )

        ManualSection.TROUBLESHOOTING -> listOf(
            GuideItem(
                title = "دستگاه میزبان در لیست پیدا نمی‌شود؛ چه کنم؟",
                description = "۱. مطمئن شوید هر دو گوشی به یک مودم وای‌فای یا هات‌اسپات مشترک وصل هستند.\n۲. دکمه پویش مجدد (Refresh) را لمس کنید.\n۳. از گزینه «ورود دستی آی‌پی» استفاده کنید و IP نشان داده شده در بالای صفحه گوشی میزبان (مثلاً 192.168.43.1) را تایپ نمایید.",
                icon = Icons.Default.Wifi
            ),
            GuideItem(
                title = "صدا در هندزفری با تاخیر می‌رسد",
                description = "هندزفری‌های بلوتوثی به طور طبیعی بین ۱۰۰ تا ۳۰۰ میلی‌ثانیه تاخیر دارند. در صفحه بلندگو، بخش «تنظیم دستی تاخیر» را باز کرده و زمان را به دقت میلی‌ثانیه هماهنگ کنید. استفاده از هندزفری سیمی تاخیر را به صفر می‌رساند.",
                icon = Icons.Default.Speed
            ),
            GuideItem(
                title = "قطع صدا در حالت خاموش شدن صفحه گوشی",
                description = "در گوشی‌های شیائومی و سامسونگ، در تنظیمات سیستم برنامه «هم‌صدا» را در حالت Battery Optimization: No restrictions (بدون محدودیت باتری) قرار دهید تا سیستم‌عامل اتصال شبکه را در پس‌زمینه نبندد.",
                icon = Icons.Default.BatteryChargingFull
            )
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (section == ManualSection.OVERVIEW) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column {
                        Image(
                            painter = painterResource(id = R.drawable.hamseda_banner_1788929859614),
                            contentDescription = "مراسم مداحی در مسجد و گوش فرا دادن با هندزفری",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = "نوای هماهنگ مداحی در مسجد و هیئت؛ بدون نقاط کور صوتی با هندزفری",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        items(items) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = item.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = item.description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    if (item.tip != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "💡 نکته کاربردی: ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.tip,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
