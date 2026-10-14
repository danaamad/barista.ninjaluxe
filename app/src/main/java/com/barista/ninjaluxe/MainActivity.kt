package com.barista.ninjaluxe

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// ============================================================================
// 1. הגדרת צבעים ומודלים (Ninja 701 Theme & Data Models)
// ============================================================================

val NinjaDarkBg = Color(0xFF121212)
val NinjaPanelGray = Color(0xFF1E1E1E)
val NinjaCardGray = Color(0xFF282828)
val NinjaOrange = Color(0xFFFF9F1C)
val NinjaBlue = Color(0xFF00B4D8)
val NinjaGreen = Color(0xFF2EC4B6)
val NinjaRed = Color(0xFFE71D36)

enum class AppTab(val title: String, val badge: String) {
    PANEL("לוח בקרה 701", "1"),
    TIMER("טיימר חליטה", "2"),
    DIAL_IN("עוזר כיול", "3")
}

enum class EspressoStyle(val title: String, val ratio: Float, val doseGrams: Float, val descHe: String) {
    RISTRETTO("Ristretto", 1.5f, 18f, "קצר ומרוכז (1:1.5)"),
    ESPRESSO("Espresso", 2.0f, 18f, "מנה כפולה קלאסית (1:2)"),
    LUNGO("Lungo", 3.0f, 18f, "אספרסו ארוך (1:3)"),
    QUAD("Luxe Quad", 2.0f, 22f, "מנה מרובעת בסלסלת ה-Luxe")
}

enum class BrewPhase(val labelHe: String, val pressureBar: Int) {
    IDLE("מוכן לחליטה", 0),
    PRE_INFUSION("השרייה מוקדמת (Pre-Infusion)", 3),
    BLOOM_PAUSE("ספיגת מים והתרחבות (Bloom)", 1),
    EXTRACTION("חליטה עיקרית בלחץ מלא", 9),
    COMPLETED("החליטה הסתיימה", 0)
}

enum class RoastLevel(val labelHe: String) {
    LIGHT("בהירה (Light)"),
    MEDIUM("בינונית (Medium)"),
    DARK("כהה (Dark)")
}

enum class TasteFeedback(val labelHe: String) {
    SOUR_ACIDIC("חמוץ / חד מדי"),
    BALANCED("מאוזן ומתוק"),
    BITTER_ASTRINGENT("מר / שרוף / יבש"),
    WATERY_WEAK("מימי / דליל")
}

enum class NinjaStrengthSetting(val dots: Int, val labelHe: String) {
    DOT_1(1, "• (יחס 1:4 ארוך)"),
    DOT_2(2, "•• (יחס 1:2.5 בינוני)"),
    DOT_3(3, "••• (יחס 1:2 מרוכז)")
}

data class DialInRecommendation(
    val recommendedGrindSize: Int,
    val recommendedTempSetting: Int,
    val recommendedStrength: NinjaStrengthSetting,
    val diagnosisTitle: String,
    val actionSteps: List<String>
)

data class NinjaAppUiState(
    val currentTab: AppTab = AppTab.PANEL,
    val selectedStyle: EspressoStyle = EspressoStyle.ESPRESSO,
    val currentGrindSetting: Int = 12,
    val recommendedGrindSetting: Int = 12,
    val tempSetting: Int = 2, // 1=Low, 2=Med, 3=High
    val strengthSetting: NinjaStrengthSetting = NinjaStrengthSetting.DOT_3,
    val roastLevel: RoastLevel = RoastLevel.MEDIUM,

    // מצב הטיימר
    val currentPhase: BrewPhase = BrewPhase.IDLE,
    val elapsedMillis: Long = 0L,
    val isRunning: Boolean = false,

    // מצב עוזר הכיול (Dial-In)
    val lastBrewSeconds: Int = 27,
    val actualYieldGrams: Float = 36f,
    val selectedTaste: TasteFeedback = TasteFeedback.BALANCED,
    val lastRecommendation: DialInRecommendation? = null
) {
    val elapsedSeconds: Float get() = elapsedMillis / 1000f
    val targetYieldGrams: Float get() = selectedStyle.doseGrams * selectedStyle.ratio
    val isOptimalWindow: Boolean get() = elapsedSeconds in 25f..30f
}

// ============================================================================
// 2. מנוע הכיול (Ninja 701 Dial-In Optimization Engine)
// ============================================================================

class NinjaDialInEngine {
    fun analyzeShot(
        currentGrind: Int,
        brewTimeSeconds: Int,
        actualYield: Float,
        roastLevel: RoastLevel,
        taste: TasteFeedback,
        currentTemp: Int
    ): DialInRecommendation {
        val steps = mutableListOf<String>()
        var newGrind = currentGrind
        var newTemp = currentTemp
        var newStrength = NinjaStrengthSetting.DOT_3
        val diagnosis: String

        // 1. ניתוח זמן הזרימה
        when {
            brewTimeSeconds < 20 -> {
                diagnosis = "תת-מיצוי חמור (זרימה מהירה מדי - Gushing)"
                val step = if (brewTimeSeconds < 15) 3 else 2
                newGrind = (currentGrind - step).coerceAtLeast(1)
                steps.add("הורד את דרגת הטחינה מ-$currentGrind ל-$newGrind כדי להאט את הזרימה ולבנות לחץ של 9 בר.")
                steps.add("ודא שהידוק הקפה (Tamping) מתבצע בלחיצה ישרה וחזקה.")
            }
            brewTimeSeconds in 20..24 -> {
                diagnosis = "זרימה מעט מהירה (נטייה לתת-מיצוי)"
                newGrind = (currentGrind - 1).coerceAtLeast(1)
                steps.add("עידון קל: הורד את דרגת הטחינה בצעד אחד ל-$newGrind.")
            }
            brewTimeSeconds > 35 -> {
                diagnosis = "חליטת יתר / חנק של המכונה (Choking)"
                val step = if (brewTimeSeconds > 42) 3 else 2
                newGrind = (currentGrind + step).coerceAtMost(25)
                steps.add("הטחינה דקה מדי וחוסמת את זרימת המים. העלה את דרגת הטחינה מ-$currentGrind ל-$newGrind.")
            }
            brewTimeSeconds in 31..35 -> {
                diagnosis = "זרימה מעט איטית"
                newGrind = (currentGrind + 1).coerceAtMost(25)
                steps.add("פתיחה קלה: העלה את דרגת הטחינה בצעד אחד ל-$newGrind.")
            }
            else -> {
                diagnosis = "חלון זמנים אידיאלי (25-30 שניות) – דיוק לפי טעם"
            }
        }

        // 2. כוונון משלים לפי מבחן הטעימה ורמת הקלייה
        when (taste) {
            TasteFeedback.SOUR_ACIDIC -> {
                if (newGrind == currentGrind) {
                    newGrind = (currentGrind - 1).coerceAtLeast(1)
                    steps.add("החמיצות מעידה על תת-מיצוי. דקק את הטחינה ל-$newGrind.")
                }
                if (roastLevel == RoastLevel.LIGHT && currentTemp < 3) {
                    newTemp = 3
                    steps.add("פולים בקלייה בהירה דורשים חום גבוה: העבר את טמפרטורת החליטה ל-3 נורות (High).")
                } else if (roastLevel == RoastLevel.MEDIUM && brewTimeSeconds >= 28) {
                    newStrength = NinjaStrengthSetting.DOT_2
                    steps.add("כדי לאזן חמיצות מבלי לחנוק את המכונה, שנה את כפתור ה-Strength ל-2 נקודות (יחס 1:2.5).")
                }
            }
            TasteFeedback.BITTER_ASTRINGENT -> {
                if (newGrind == currentGrind) {
                    newGrind = (currentGrind + 1).coerceAtMost(25)
                    steps.add("המרירות מעידה על מיצוי יתר. העלה את דרגת הטחינה ל-$newGrind.")
                }
                if (roastLevel == RoastLevel.DARK && currentTemp > 1) {
                    newTemp = 1
                    steps.add("פולים בקלייה כהה רגישים לחום: הורד את טמפרטורת החליטה לנורה אחת (Low).")
                }
                if (actualYield > 40f) {
                    newStrength = NinjaStrengthSetting.DOT_3
                    steps.add("כמות הנוזל בכוס גבוהה מדי. ודא שכפתור ה-Strength מכוון ל-3 נקודות (יחס קצר 1:2).")
                }
            }
            TasteFeedback.WATERY_WEAK -> {
                newStrength = NinjaStrengthSetting.DOT_3
                steps.add("הגדר את כפתור ה-Strength ל-3 נקודות (יחס מרוכז 1:2).")
                steps.add("אם הכמות בכוס עדיין עוברת 40 גרם, עצור את החליטה ידנית בלחיצה על START/STOP ב-36 גרם.")
            }
            TasteFeedback.BALANCED -> {
                if (steps.isEmpty()) {
                    steps.add("האספרסו מכויל בצורה מושלמת! שמור על הגדרות אלו עבור הפולים הנוכחיים.")
                }
            }
        }

        if (kotlin.math.abs(newGrind - currentGrind) >= 2) {
            steps.add("שים לב: בגלל אגירת קפה קלה במטחנה (Retention), השינוי המלא יורגש החל מהשוט השני.")
        }

        return DialInRecommendation(
            recommendedGrindSize = newGrind,
            recommendedTempSetting = newTemp,
            recommendedStrength = newStrength,
            diagnosisTitle = diagnosis,
            actionSteps = steps
        )
    }
}

// ============================================================================
// 3. ViewModel מאוחד לניהול כל המסכים והטיימר
// ============================================================================

class NinjaCompanionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NinjaAppUiState())
    val uiState: StateFlow<NinjaAppUiState> = _uiState.asStateFlow()

    private val dialInEngine = NinjaDialInEngine()
    private var timerJob: Job? = null

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun selectStyle(style: EspressoStyle) {
        if (_uiState.value.isRunning) return
        _uiState.update {
            it.copy(
                selectedStyle = style,
                actualYieldGrams = style.doseGrams * style.ratio
            )
        }
    }

    fun adjustGrind(delta: Int) {
        _uiState.update {
            val updated = (it.currentGrindSetting + delta).coerceIn(1, 25)
            it.copy(currentGrindSetting = updated)
        }
    }

    fun cycleTemp() {
        _uiState.update {
            val nextTemp = if (it.tempSetting >= 3) 1 else it.tempSetting + 1
            it.copy(tempSetting = nextTemp)
        }
    }

    fun cycleStrength() {
        _uiState.update {
            val next = when (it.strengthSetting) {
                NinjaStrengthSetting.DOT_1 -> NinjaStrengthSetting.DOT_2
                NinjaStrengthSetting.DOT_2 -> NinjaStrengthSetting.DOT_3
                NinjaStrengthSetting.DOT_3 -> NinjaStrengthSetting.DOT_1
            }
            it.copy(strengthSetting = next)
        }
    }

    fun selectRoast(roast: RoastLevel) {
        val suggestedTemp = when (roast) {
            RoastLevel.LIGHT -> 3
            RoastLevel.MEDIUM -> 2
            RoastLevel.DARK -> 1
        }
        _uiState.update { it.copy(roastLevel = roast, tempSetting = suggestedTemp) }
    }

    // --- ניהול הטיימר ---
    fun toggleTimer() {
        if (_uiState.value.isRunning) stopBrew() else startBrew()
    }

    private fun startBrew() {
        timerJob?.cancel()
        _uiState.update {
            it.copy(
                isRunning = true,
                elapsedMillis = 0L,
                currentPhase = BrewPhase.PRE_INFUSION
            )
        }
        timerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (_uiState.value.isRunning) {
                delay(100L)
                val elapsed = System.currentTimeMillis() - startTime
                val sec = elapsed / 1000f
                val phase = when {
                    sec <= 4.0f -> BrewPhase.PRE_INFUSION
                    sec <= 6.5f -> BrewPhase.BLOOM_PAUSE
                    else -> BrewPhase.EXTRACTION
                }
                _uiState.update { it.copy(elapsedMillis = elapsed, currentPhase = phase) }
            }
        }
    }

    private fun stopBrew() {
        timerJob?.cancel()
        val finalSec = (_uiState.value.elapsedMillis / 1000L).toInt().coerceAtLeast(1)
        _uiState.update {
            it.copy(
                isRunning = false,
                currentPhase = BrewPhase.COMPLETED,
                lastBrewSeconds = finalSec
            )
        }
    }

    fun resetTimer() {
        timerJob?.cancel()
        _uiState.update {
            it.copy(
                isRunning = false,
                elapsedMillis = 0L,
                currentPhase = BrewPhase.IDLE
            )
        }
    }

    // --- ניהול עוזר הכיול ---
    fun updateDialInInputs(seconds: Int? = null, yieldGrams: Float? = null, taste: TasteFeedback? = null) {
        _uiState.update {
            it.copy(
                lastBrewSeconds = seconds ?: it.lastBrewSeconds,
                actualYieldGrams = yieldGrams ?: it.actualYieldGrams,
                selectedTaste = taste ?: it.selectedTaste
            )
        }
    }

    fun calculateDialIn() {
        val s = _uiState.value
        val rec = dialInEngine.analyzeShot(
            currentGrind = s.currentGrindSetting,
            brewTimeSeconds = s.lastBrewSeconds,
            actualYield = s.actualYieldGrams,
            roastLevel = s.roastLevel,
            taste = s.selectedTaste,
            currentTemp = s.tempSetting
        )
        _uiState.update {
            it.copy(
                lastRecommendation = rec,
                recommendedGrindSetting = rec.recommendedGrindSize
            )
        }
    }

    fun applyRecommendationToPanel() {
        val rec = _uiState.value.lastRecommendation ?: return
        _uiState.update {
            it.copy(
                currentGrindSetting = rec.recommendedGrindSize,
                recommendedGrindSetting = rec.recommendedGrindSize,
                tempSetting = rec.recommendedTempSetting,
                strengthSetting = rec.recommendedStrength,
                currentTab = AppTab.PANEL
            )
        }
    }
}

// ============================================================================
// 4. MainActivity והמעטפת הראשית (Scaffold + RTL)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // השארת המסך דולק בעת עבודה מול מכונת הקפה
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = NinjaOrange,
                        secondary = NinjaBlue,
                        background = NinjaDarkBg,
                        surface = NinjaPanelGray
                    )
                ) {
                    val viewModel: NinjaCompanionViewModel = viewModel()
                    NinjaMainApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun NinjaMainApp(viewModel: NinjaCompanionViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = NinjaDarkBg,
        bottomBar = {
            NavigationBar(containerColor = NinjaPanelGray) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.currentTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(if (state.currentTab == tab) NinjaOrange else NinjaCardGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.badge,
                                    color = if (state.currentTab == tab) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontWeight = if (state.currentTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = NinjaOrange,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (state.currentTab) {
                AppTab.PANEL -> VirtualNinjaPanelScreen(state, viewModel)
                AppTab.TIMER -> NinjaBrewTimerScreen(state, viewModel)
                AppTab.DIAL_IN -> NinjaDialInScreen(state, viewModel)
            }
        }
    }
}

// ============================================================================
// 5. מסך 1: לוח הבקרה הווירטואלי (Ninja 701 Control Panel)
// ============================================================================

@Composable
fun VirtualNinjaPanelScreen(state: NinjaAppUiState, viewModel: NinjaCompanionViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // כותרת עליונה
        Text(
            text = "NINJA LUXE CAFÉ • ES701",
            color = NinjaOrange,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // כרטיס Barista Assist ודרגת טחינה
        Card(
            colors = CardDefaults.cardColors(containerColor = NinjaPanelGray),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("BARISTA ASSIST GRIND", color = NinjaOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("המלצת טחינה: ${state.recommendedGrindSetting}", color = NinjaBlue, fontSize = 14.sp)
                    }
                    Surface(
                        color = NinjaCardGray,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "טווח: 1-25",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // בורר דרגת הטחינה הנוכחית
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.adjustGrind(-1) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = NinjaCardGray)
                    ) {
                        Text("- דק יותר", color = Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${state.currentGrindSetting}",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text("דרגה נוכחית", color = Color.Gray, fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.adjustGrind(1) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = NinjaCardGray)
                    ) {
                        Text("+ גס יותר", color = Color.White)
                    }
                }
            }
        }

        // בחירת משקה (חוגת האספרסו)
        Card(
            colors = CardDefaults.cardColors(containerColor = NinjaPanelGray),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("בחירת משקה וסלסלה (ESPRESSO STYLES)", color = Color.LightGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                EspressoStyle.entries.forEach { style ->
                    val isSelected = state.selectedStyle == style
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { viewModel.selectStyle(style) },
                        color = if (isSelected) NinjaOrange.copy(alpha = 0.18f) else NinjaCardGray,
                        shape = RoundedCornerShape(12.dp),
                        border = if (isSelected) BorderStroke(1.5.dp, NinjaOrange) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = style.title,
                                    color = if (isSelected) NinjaOrange else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(text = style.descHe, color = Color.LightGray, fontSize = 12.sp)
                            }
                            Text(
                                text = "${style.doseGrams.toInt()}g -> ${(style.doseGrams * style.ratio).toInt()}g",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // הגדרות קלייה, טמפרטורה ויחס חוזק (Strength & Brew Temp)
        Card(
            colors = CardDefaults.cardColors(containerColor = NinjaPanelGray),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("סוג פולים ופרמטרי מכונה", color = Color.LightGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                // בורר רמת קלייה
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoastLevel.entries.forEach { roast ->
                        val selected = state.roastLevel == roast
                        OutlinedButton(
                            onClick = { viewModel.selectRoast(roast) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) NinjaOrange else Color.Transparent,
                                contentColor = if (selected) Color.Black else Color.White
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(roast.labelHe, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }

                HorizontalDivider(color = NinjaCardGray)

                // לחצן טמפרטורה ולחצן Strength
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // כפתור טמפרטורה (3 נורות)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.cycleTemp() },
                        color = NinjaCardGray,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("BREW TEMP", color = Color.Gray, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "● ".repeat(state.tempSetting) + "○ ".repeat(3 - state.tempSetting),
                                color = NinjaRed,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (state.tempSetting) {
                                    1 -> "נמוכה (פולים כהים)"
                                    2 -> "בינונית (סטנדרט)"
                                    else -> "גבוהה (פולים בהירים)"
                                },
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // כפתור Strength
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.cycleStrength() },
                        color = NinjaCardGray,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("STRENGTH", color = Color.Gray, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "● ".repeat(state.strengthSetting.dots) + "○ ".repeat(3 - state.strengthSetting.dots),
                                color = NinjaOrange,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.strengthSetting.labelHe,
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // כפתור מעבר מהיר לטיימר החליטה
        Button(
            onClick = { viewModel.selectTab(AppTab.TIMER) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NinjaOrange),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "עבור לטיימר החליטה (START BREW)",
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        }
    }
}

// ============================================================================
// 6. מסך 2: טיימר חליטה חכם (Live Brew Timer & Pressure Gauge)
// ============================================================================

@Composable
fun NinjaBrewTimerScreen(state: NinjaAppUiState, viewModel: NinjaCompanionViewModel) {
    val ringColor by animateColorAsState(
        targetValue = when {
            !state.isRunning && state.currentPhase == BrewPhase.IDLE -> Color(0xFF4A4A4A)
            state.currentPhase == BrewPhase.PRE_INFUSION || state.currentPhase == BrewPhase.BLOOM_PAUSE -> NinjaBlue
            state.isOptimalWindow -> NinjaGreen
            state.elapsedSeconds > 30f -> NinjaRed
            else -> NinjaOrange
        },
        label = "RingColor"
    )

    val progress by animateFloatAsState(
        targetValue = (state.elapsedSeconds / 35f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 100),
        label = "RingProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // סיכום נתוני החליטה בראש המסך
        Card(
            colors = CardDefaults.cardColors(containerColor = NinjaPanelGray),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TelemetryItem("משקה", state.selectedStyle.title)
                TelemetryItem("דרגת טחינה", "${state.currentGrindSetting}")
                TelemetryItem("קפה (Dose)", "${state.selectedStyle.doseGrams.toInt()}g")
                TelemetryItem("יעד נוזל", "${state.targetYieldGrams.toInt()}g")
            }
        }

        // שעון החליטה הטבעתי
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(270.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Color(0xFF2A2A2A),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 28f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = 135f,
                    sweepAngle = 270f * progress,
                    useCenter = false,
                    style = Stroke(width = 28f, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(Locale.US, "%.1f s", state.elapsedSeconds),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.currentPhase.labelHe,
                    color = ringColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = NinjaCardGray,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "לחץ משוער: ${state.currentPhase.pressureBar} BAR",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // כפתורי שליטה ומעבר לאבחון
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.resetTimer() },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("איפוס")
                }

                Button(
                    onClick = { viewModel.toggleTimer() },
                    modifier = Modifier
                        .weight(2f)
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRunning) NinjaRed else NinjaOrange
                    )
                ) {
                    Text(
                        text = if (state.isRunning) "עצור חליטה (STOP)" else "התחל חליטה (START)",
                        color = if (state.isRunning) Color.White else Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }

            if (state.currentPhase == BrewPhase.COMPLETED) {
                FilledTonalButton(
                    onClick = {
                        viewModel.calculateDialIn()
                        viewModel.selectTab(AppTab.DIAL_IN)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = NinjaGreen)
                ) {
                    Text(
                        text = "נתח תוצאות שוט (${state.lastBrewSeconds} שניות) בעוזר הכיול ->",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ============================================================================
// 7. מסך 3: עוזר כיול אספרסו (Dial-In Assistant)
// ============================================================================

@Composable
fun NinjaDialInScreen(state: NinjaAppUiState, viewModel: NinjaCompanionViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "עוזר כיול (Dial-In Assistant)",
            color = NinjaOrange,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )

        // הזנת נתוני השוט האחרון
        Card(
            colors = CardDefaults.cardColors(containerColor = NinjaPanelGray),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // סליידר זמן חליטה
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("זמן חליטה בפועל:", color = Color.LightGray, fontSize = 14.sp)
                        Text(
                            text = "${state.lastBrewSeconds} שניות",
                            color = if (state.lastBrewSeconds in 25..30) NinjaGreen else NinjaOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = state.lastBrewSeconds.toFloat(),
                        onValueChange = { viewModel.updateDialInInputs(seconds = it.toInt()) },
                        valueRange = 10f..50f,
                        colors = SliderDefaults.colors(thumbColor = NinjaOrange, activeTrackColor = NinjaOrange)
                    )
                }

                // סליידר משקל נוזל בכוס
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("משקל המשקה בכוס (Yield):", color = Color.LightGray, fontSize = 14.sp)
                        Text(
                            text = "${state.actualYieldGrams.toInt()} גרם",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = state.actualYieldGrams,
                        onValueChange = { viewModel.updateDialInInputs(yieldGrams = it) },
                        valueRange = 15f..70f,
                        colors = SliderDefaults.colors(thumbColor = NinjaBlue, activeTrackColor = NinjaBlue)
                    )
                }

                // בחירת מבחן טעימה
                Text("איך הטעם בכוס?", color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                TasteFeedback.entries.forEach { taste ->
                    val isSelected = state.selectedTaste == taste
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateDialInInputs(taste = taste) },
                        color = if (isSelected) NinjaOrange.copy(alpha = 0.2f) else NinjaCardGray,
                        shape = RoundedCornerShape(10.dp),
                        border = if (isSelected) BorderStroke(1.dp, NinjaOrange) else null
                    ) {
                        Text(
                            text = taste.labelHe,
                            color = if (isSelected) NinjaOrange else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.calculateDialIn() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NinjaOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("חשב המלצות כיול למכונה", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // הצגת כרטיס ההמלצות (אם חושב)
        state.lastRecommendation?.let { rec ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NinjaCardGray),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, NinjaGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = rec.diagnosisTitle,
                        color = NinjaGreen,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NinjaPanelGray, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        TelemetryItem("טחינה מומלצת", "${rec.recommendedGrindSize}")
                        TelemetryItem("טמפרטורה", "${rec.recommendedTempSetting} נורות")
                        TelemetryItem("Strength", "${rec.recommendedStrength.dots} נק'")
                    }

                    rec.actionSteps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("•", color = NinjaOrange, fontWeight = FontWeight.Bold)
                            Text(text = step, color = Color.White, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = { viewModel.applyRecommendationToPanel() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NinjaGreen)
                    ) {
                        Text(
                            text = "החל הגדרות אלו על לוח הבקרה",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
