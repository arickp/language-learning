package io.github.arickp.languagelearning

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Cream = Color(0xFFFFF9F2)
private val Ink = Color(0xFF23313A)
private val Green = Color(0xFF2C6E63)
private val Gold = Color(0xFFE5A63B)
private val Red = Color(0xFFB64B4B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LanguageLearningTheme { WordBankLoader() } }
    }
}

@Composable
private fun WordBankLoader() {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Result<Boolean>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = QuizData.load(context)
    }
    when (val current = result) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text("Loading the word bank…", fontWeight = FontWeight.Bold)
            }
        }
        else -> current.fold(
            onSuccess = { refreshed ->
                Box {
                    QuizGame()
                    if (!refreshed) {
                        Text(
                            "Offline word bank",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Gold, RoundedCornerShape(bottomStart = 8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            },
            onFailure = { error ->
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Word bank unavailable", fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Text(error.message ?: "Could not load quiz data.", modifier = Modifier.padding(18.dp), textAlign = TextAlign.Center)
                    FocusActionButton(
                        label = "Try again",
                        onClick = { attempt++ },
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 58.dp),
                        primary = true
                    )
                }
            }
        )
    }
}

@Composable
private fun LanguageLearningTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green, secondary = Gold, background = Cream,
            surface = Color.White, onPrimary = Color.White, onBackground = Ink, onSurface = Ink
        ),
        typography = Typography(),
        content = content
    )
}

private enum class Screen { HOME, SETTINGS, QUIZ, RESULTS }

@Composable
private fun QuizGame() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("language_learning_settings", 0) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var allowExplicitContent by remember {
        mutableStateOf(preferences.getBoolean("allow_explicit_content", false))
    }
    var selectedCategory by remember { mutableStateOf<QuizCategory?>(null) }
    var selectedDifficulty by remember {
        mutableStateOf(
            runCatching { Difficulty.valueOf(preferences.getString("difficulty", Difficulty.EASY.name)!!) }
                .getOrDefault(Difficulty.EASY)
        )
    }
    var selectedVariant by remember {
        mutableStateOf(
            runCatching {
                LanguageVariant.valueOf(preferences.getString("language_variant", LanguageVariant.GERMANY.name)!!)
            }.getOrDefault(LanguageVariant.GERMANY)
        )
    }
    var questions by remember { mutableStateOf(emptyList<QuizItem>()) }
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var bestStreak by remember { mutableIntStateOf(0) }
    var recentQuestionKeys by remember { mutableStateOf(emptyList<String>()) }
    var answeredChoices by remember { mutableStateOf(emptyMap<Int, String>()) }
    var revealedHintCounts by remember { mutableStateOf(emptyMap<Int, Int>()) }
    var exampleStates by remember { mutableStateOf(emptyMap<Int, ExampleDiscoveryState>()) }
    var exampleSources by remember { mutableStateOf(emptyMap<Int, ExampleSource>()) }
    var startError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun start(variant: LanguageVariant, category: QuizCategory?) {
        selectedVariant = variant
        val language = variant.language
        selectedCategory = category
        val exactPool = QuizData.items.filter {
            it.language == language &&
                it.appliesTo(variant) &&
                it.difficulty == selectedDifficulty &&
                (category == null || it.category == category)
        }
        val supplementalPool = QuizData.items.filter {
            it.language == language &&
                it.appliesTo(variant) &&
                it.difficulty != selectedDifficulty &&
                (category == null || it.category == category)
        }.sortedBy { kotlin.math.abs(it.difficulty.ordinal - selectedDifficulty.ordinal) }
        val pool = if (exactPool.size >= 10) {
            exactPool
        } else {
            exactPool + supplementalPool.take(10 - exactPool.size)
        }
        val recentKeySet = recentQuestionKeys.toSet()
        val notRecentlySeen = pool.filter { it.historyKey() !in recentKeySet }.shuffled()
        val recentlySeen = pool
            .filter { it.historyKey() in recentKeySet }
            .shuffled()
            .sortedBy { recentQuestionKeys.lastIndexOf(it.historyKey()) }
        questions = (notRecentlySeen + recentlySeen).take(10)
        if (questions.isEmpty()) {
            val label = category?.label?.lowercase() ?: "mixed"
            startError = "No $label questions for ${variant.label} · ${selectedDifficulty.label}."
            return
        }
        startError = null
        recentQuestionKeys = (recentQuestionKeys + questions.map { it.historyKey() }).takeLast(30)
        index = 0; score = 0; streak = 0; bestStreak = 0
        answeredChoices = emptyMap()
        revealedHintCounts = emptyMap()
        exampleStates = emptyMap()
        exampleSources = emptyMap()
        screen = Screen.QUIZ
    }

    BackHandler(enabled = screen != Screen.HOME) {
        when (screen) {
            Screen.HOME -> Unit
            Screen.SETTINGS -> screen = Screen.HOME
            Screen.QUIZ -> {
                if (index > 0) index-- else screen = Screen.HOME
            }
            Screen.RESULTS -> {
                index = questions.lastIndex
                screen = Screen.QUIZ
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Cream) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                variant = selectedVariant,
                onVariantChange = { variant ->
                    selectedVariant = variant
                    preferences.edit().putString("language_variant", variant.name).apply()
                },
                difficulty = selectedDifficulty,
                onDifficultyChange = { difficulty ->
                    selectedDifficulty = difficulty
                    preferences.edit().putString("difficulty", difficulty.name).apply()
                },
                onStart = ::start,
                onSettings = { screen = Screen.SETTINGS },
                startError = startError
            )
            Screen.SETTINGS -> SettingsScreen(
                allowExplicitContent = allowExplicitContent,
                onAllowExplicitContentChange = { allowed ->
                    allowExplicitContent = allowed
                    preferences.edit().putBoolean("allow_explicit_content", allowed).apply()
                },
                onBack = { screen = Screen.HOME }
            )
            Screen.QUIZ -> {
                val item = questions.getOrNull(index)
                if (item != null) {
                    QuizScreen(
                        item = item, number = index + 1, total = questions.size,
                        variant = selectedVariant,
                        score = score, streak = streak,
                        chosen = answeredChoices[index],
                        onChoose = { answer -> answeredChoices = answeredChoices + (index to answer) },
                        revealedHintCount = revealedHintCounts[index] ?: 0,
                        exampleState = exampleStates[index] ?: ExampleDiscoveryState.Idle,
                        exampleSource = exampleSources[index] ?: ExampleSource.REDDIT,
                        onExampleSourceChange = { source ->
                            exampleSources = exampleSources + (index to source)
                            exampleStates = exampleStates + (index to ExampleDiscoveryState.Idle)
                        },
                        onRevealHint = {
                            val currentCount = revealedHintCounts[index] ?: 0
                            if (currentCount < item.hints.size) {
                                revealedHintCounts = revealedHintCounts + (index to currentCount + 1)
                            }
                        },
                        onAnswer = { correct ->
                            if (correct) {
                                AnswerSoundPlayer.correct()
                                score++; streak++; bestStreak = maxOf(bestStreak, streak)
                            } else {
                                AnswerSoundPlayer.incorrect()
                                streak = 0
                            }
                        },
                        onFindExample = { source ->
                            val requestedIndex = index
                            val requestedItem = questions[requestedIndex]
                            if (exampleStates[requestedIndex] !is ExampleDiscoveryState.Loading) {
                                exampleStates = exampleStates + (requestedIndex to ExampleDiscoveryState.Loading)
                                coroutineScope.launch {
                                    val result = ExampleDiscovery.find(requestedItem, source, allowExplicitContent)
                                    exampleStates = exampleStates + (requestedIndex to result)
                                }
                            }
                        },
                        onNext = {
                            if (index == questions.lastIndex) screen = Screen.RESULTS else index++
                        },
                        onQuit = { screen = Screen.HOME }
                    )
                } else {
                    LaunchedEffect(questions, index) { screen = Screen.HOME }
                }
            }
            Screen.RESULTS -> ResultsScreen(
                score = score, total = questions.size, bestStreak = bestStreak,
                language = selectedVariant.language,
                onAgain = { start(selectedVariant, selectedCategory) }, onHome = { screen = Screen.HOME }
            )
        }
    }
}

private fun QuizItem.historyKey(): String = "${language.name}|${category.name}|$prompt"

@Composable
private fun HomeScreen(
    variant: LanguageVariant,
    onVariantChange: (LanguageVariant) -> Unit,
    difficulty: Difficulty,
    onDifficultyChange: (Difficulty) -> Unit,
    onStart: (LanguageVariant, QuizCategory?) -> Unit,
    onSettings: () -> Unit,
    startError: String? = null
) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var difficultyMenuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.widthIn(max = 860.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LANGUAGE LEARNING", color = Green, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text("Train your ${variant.language.label}", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Text("Ten questions. Instant feedback. A fresh mix every time.", fontSize = 18.sp, color = Ink.copy(alpha = .7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        FocusActionButton(
            label = "⚙ Settings",
            onClick = onSettings,
            modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().heightIn(min = 52.dp),
            primary = false
        )
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth()) {
            FocusActionButton(
                label = "${variant.flag}  ${variant.label}  ▾",
                onClick = { languageMenuExpanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                primary = false
            )
            DropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false },
                modifier = Modifier.widthIn(min = 320.dp, max = 620.dp).heightIn(max = 520.dp)
            ) {
                LanguageVariant.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${option.flag}  ${option.label}${if (option == variant) "  ✓" else ""}",
                                fontSize = 17.sp,
                                fontWeight = if (option == variant) FontWeight.Black else FontWeight.Medium
                            )
                        },
                        onClick = {
                            onVariantChange(option)
                            languageMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth()) {
            FocusActionButton(
                label = "Difficulty: ${difficulty.label}  ▾",
                onClick = { difficultyMenuExpanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                primary = false
            )
            DropdownMenu(
                expanded = difficultyMenuExpanded,
                onDismissRequest = { difficultyMenuExpanded = false },
                modifier = Modifier.widthIn(min = 320.dp, max = 620.dp)
            ) {
                Difficulty.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${option.label}${if (option == difficulty) "  ✓" else ""}",
                                    fontSize = 17.sp,
                                    fontWeight = if (option == difficulty) FontWeight.Black else FontWeight.Bold
                                )
                                Text(option.description, fontSize = 13.sp, color = Ink.copy(alpha = .66f))
                            }
                        },
                        onClick = {
                            onDifficultyChange(option)
                            difficultyMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (startError != null) {
            Text(
                startError,
                color = Red,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        CategoryButton("Mixed challenge", "Vocabulary + articles + grammar", Gold) { onStart(variant, null) }
        CategoryButton("Vocabulary", "Words, expressions, and verbs", Green) { onStart(variant, QuizCategory.VOCABULARY) }
        CategoryButton("Articles", "Practice noun gender and articles", Color(0xFF4A67A1)) { onStart(variant, QuizCategory.ARTICLES) }
        CategoryButton("Grammar", "Pronouns, verbs, cases, and patterns", Color(0xFF8A5A9C)) { onStart(variant, QuizCategory.GRAMMAR) }
        }
    }
}

@Composable
private fun SettingsScreen(
    allowExplicitContent: Boolean,
    onAllowExplicitContentChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 760.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.height(16.dp))
            Text("Settings", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Ink)
            Spacer(Modifier.height(20.dp))
            Text(
                "This app searches the Internet for posts using the words you learn. Choose how explicit content should be handled.",
                fontSize = 21.sp,
                lineHeight = 29.sp,
                color = Ink
            )
            Spacer(Modifier.height(24.dp))
            SettingChoice(
                title = "Block explicit content",
                subtitle = "Recommended · This is the default",
                selected = !allowExplicitContent,
                onClick = { onAllowExplicitContentChange(false) }
            )
            Spacer(Modifier.height(12.dp))
            SettingChoice(
                title = "Allow explicit content",
                subtitle = "NSFW posts may be suggested and will be clearly labelled",
                selected = allowExplicitContent,
                onClick = { onAllowExplicitContentChange(true) }
            )
        }
    }
}

@Composable
private fun SettingChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected || focused) 4.dp else 1.dp, if (selected || focused) Gold else Ink.copy(alpha = .3f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Gold.copy(alpha = .12f) else Color.White)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${if (focused) "▶ " else ""}$title",
                fontSize = 20.sp,
                fontWeight = if (selected || focused) FontWeight.Black else FontWeight.Bold
            )
            Text(subtitle, color = Ink.copy(alpha = .68f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun CategoryButton(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "$title focus")
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .heightIn(min = 72.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        border = BorderStroke(if (focused) 5.dp else 0.dp, if (focused) Color.White else Color.Transparent)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "${if (focused) "▶  " else ""}$title",
                fontSize = if (focused) 22.sp else 20.sp,
                fontWeight = if (focused) FontWeight.Black else FontWeight.Bold
            )
            Text(subtitle, color = Color.White.copy(alpha = .82f))
        }
    }
}

@Composable
private fun QuizScreen(
    item: QuizItem, number: Int, total: Int, score: Int, streak: Int,
    variant: LanguageVariant,
    chosen: String?, onChoose: (String) -> Unit,
    revealedHintCount: Int, onRevealHint: () -> Unit,
    exampleState: ExampleDiscoveryState,
    exampleSource: ExampleSource,
    onExampleSourceChange: (ExampleSource) -> Unit,
    onFindExample: (ExampleSource) -> Unit,
    onAnswer: (Boolean) -> Unit, onNext: () -> Unit, onQuit: () -> Unit
) {
    var autoAdvanceThisAnswer by remember(item) { mutableStateOf(false) }
    val autoAdvanceProgress = remember(item) { Animatable(1f) }
    val questionScrollState = rememberScrollState()
    val context = LocalContext.current
    val pronunciationScope = rememberCoroutineScope()
    var pronunciationState by remember(item, variant) { mutableStateOf<PronunciationState>(PronunciationState.Idle) }
    var practiceSentence by remember(item, variant) { mutableStateOf<PracticeSentence?>(null) }
    var practiceBusy by remember(item, variant) { mutableStateOf(false) }
    var isRecording by remember(item, variant) { mutableStateOf(false) }
    var practiceFeedback by remember(item, variant) { mutableStateOf<String?>(null) }
    val wavRecorder = remember(item, variant) { WavRecorder(context) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            wavRecorder.start(pronunciationScope)
            isRecording = true
        }
    }
    val categoryPool = answerChoicesFor(item)
    val options = remember(item) {
        (categoryPool.filter { it != item.answer }.shuffled().take(3) + item.answer).shuffled()
    }

    LaunchedEffect(item) {
        questionScrollState.scrollTo(0)
    }

    LaunchedEffect(chosen, autoAdvanceThisAnswer) {
        if (chosen != null && autoAdvanceThisAnswer) {
            autoAdvanceProgress.snapTo(1f)
            autoAdvanceProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 10_000, easing = LinearEasing)
            )
            onNext()
        } else {
            autoAdvanceProgress.snapTo(1f)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(24.dp)
            .widthIn(max = 1100.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onQuit) { Text("← Quit") }
            LinearProgressIndicator(
                progress = { number.toFloat() / total },
                modifier = Modifier.weight(1f).height(8.dp), color = Gold, trackColor = Ink.copy(alpha = .1f)
            )
            Spacer(Modifier.width(16.dp))
            Text("$number / $total", fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${item.category.label.uppercase()} · ${item.difficulty.label.uppercase()}", color = Green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Score $score   🔥 $streak", fontWeight = FontWeight.Bold)
        }
        Card(
            Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.fillMaxSize().padding(28.dp).verticalScroll(questionScrollState),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text(item.prompt, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                item.translation?.let { translation ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        translation,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.copy(alpha = .68f),
                        textAlign = TextAlign.Center
                    )
                }
                AnimatedVisibility(revealedHintCount > 0) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                            .background(Gold.copy(alpha = .13f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        item.hints.take(revealedHintCount).forEachIndexed { hintIndex, hint ->
                            Text(
                                "Hint ${hintIndex + 1}: $hint",
                                color = Ink,
                                fontSize = 17.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                options.forEach { option ->
                    AnswerOptionButton(
                        option = option,
                        correctAnswer = item.answer,
                        chosen = chosen,
                        answerMarker = answerMarker(item, option),
                        onClick = {
                            if (chosen == null) {
                                onChoose(option)
                                autoAdvanceThisAnswer = true
                                onAnswer(option == item.answer)
                            }
                        }
                    )
                }
                AnimatedVisibility(chosen != null) {
                    Column(Modifier.padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val success = if (item.language == Language.GERMAN) {
                            "Richtig! · Correct! ✓"
                        } else {
                            "Correct ! ✓"
                        }
                        Text(if (chosen == item.answer) success else "Not quite", color = if (chosen == item.answer) Green else Red, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(item.explanation, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center)
                        item.spokenText?.let { spokenText ->
                            Spacer(Modifier.height(6.dp))
                            FocusActionButton(
                                label = when (pronunciationState) {
                                    PronunciationState.Loading -> "Preparing pronunciation…"
                                    PronunciationState.Playing -> "🔊 Play “$spokenText” again"
                                    PronunciationState.Unavailable -> "🔊 Try pronunciation again"
                                    PronunciationState.Idle -> "🔊 Hear “$spokenText”"
                                },
                                onClick = {
                                    if (pronunciationState !is PronunciationState.Loading) {
                                        autoAdvanceThisAnswer = false
                                        pronunciationState = PronunciationState.Loading
                                        pronunciationScope.launch {
                                            pronunciationState = PronunciationPlayer.play(context, spokenText, variant, item.spokenLanguage)
                                        }
                                    }
                                },
                                enabled = pronunciationState !is PronunciationState.Loading,
                                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(min = 54.dp),
                                primary = false
                            )
                            if (pronunciationState is PronunciationState.Unavailable) {
                                Text(
                                    "Pronunciation is unavailable. Check the companion server.",
                                    color = Ink.copy(alpha = .65f),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (practiceSentence == null) {
                                FocusActionButton(
                                    label = if (practiceBusy) "Creating a sample sentence…" else "✨ Give me a sample sentence",
                                    onClick = {
                                        autoAdvanceThisAnswer = false
                                        practiceBusy = true
                                        pronunciationScope.launch {
                                            SpeakingPracticeClient.sentence(spokenText, variant, item.spokenLanguage)
                                                .onSuccess { practiceSentence = it }
                                                .onFailure { practiceFeedback = it.message ?: "Could not create a sentence." }
                                            practiceBusy = false
                                        }
                                    },
                                    enabled = !practiceBusy,
                                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(min = 54.dp),
                                    primary = false
                                )
                            } else {
                                val sample = practiceSentence!!
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Gold.copy(alpha = .10f)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(sample.sentence, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                        Text(sample.translation, color = Ink.copy(alpha = .68f), textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(10.dp))
                                        FocusActionButton(
                                            label = "🔊 Hear the sample",
                                            onClick = {
                                                pronunciationScope.launch { PronunciationPlayer.play(context, sample.sentence, variant, item.spokenLanguage) }
                                            },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                            primary = false
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        FocusActionButton(
                                            label = when {
                                                practiceBusy -> "AI is listening…"
                                                isRecording -> "⏹ Stop and check my pronunciation"
                                                else -> "🎙 Read it aloud"
                                            },
                                            onClick = {
                                                autoAdvanceThisAnswer = false
                                                if (isRecording) {
                                                    isRecording = false; practiceBusy = true; practiceFeedback = null
                                                    pronunciationScope.launch {
                                                        runCatching { wavRecorder.stop() }
                                                            .onSuccess { file ->
                                                                SpeakingPracticeClient.evaluate(file, sample.sentence, variant, item.spokenLanguage)
                                                                    .onSuccess { practiceFeedback = it }
                                                                    .onFailure { practiceFeedback = it.message ?: "Could not evaluate the recording." }
                                                            }
                                                            .onFailure { practiceFeedback = it.message ?: "Could not save the recording." }
                                                        practiceBusy = false
                                                    }
                                                } else if (wavRecorder.hasPermission()) {
                                                    wavRecorder.start(pronunciationScope); isRecording = true
                                                } else {
                                                    microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                                }
                                            },
                                            enabled = !practiceBusy,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                            primary = isRecording
                                        )
                                        practiceFeedback?.let {
                                            AgentMarkdownText(
                                                markdown = it,
                                                modifier = Modifier.padding(top = 12.dp),
                                                fontSize = 16.sp,
                                                lineHeight = 22.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (chosen == item.answer && ExampleDiscovery.isConfigured) {
                            Spacer(Modifier.height(8.dp))
                            ExampleSourcePicker(
                                language = item.language,
                                selected = exampleSource,
                                onSelected = onExampleSourceChange
                            )
                            Spacer(Modifier.height(8.dp))
                            when (val discovery = exampleState) {
                                ExampleDiscoveryState.Idle -> FocusActionButton(
                                    label = "🔎 Find an example on ${exampleSource.label}",
                                    onClick = { autoAdvanceThisAnswer = false; onFindExample(exampleSource) },
                                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(min = 54.dp),
                                    primary = false
                                )
                                ExampleDiscoveryState.Loading -> {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                    Text("Searching ${exampleSource.label}…", modifier = Modifier.padding(top = 6.dp))
                                }
                                is ExampleDiscoveryState.Found -> {
                                    AgentMarkdownText(
                                        markdown = discovery.result.summary,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp
                                    )
                                    if (discovery.result.nsfw) {
                                        Text(
                                            "NSFW",
                                            color = Red,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .border(1.dp, Red, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    FocusActionButton(
                                        label = "Open on ${discovery.result.source}: ${discovery.result.title}",
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(discovery.result.url)))
                                        },
                                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 54.dp),
                                        primary = false
                                    )
                                }
                                ExampleDiscoveryState.Unavailable -> Text(
                                    "Couldn’t find a public example on ${exampleSource.label} right now.",
                                    color = Ink.copy(alpha = .65f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                        if (autoAdvanceThisAnswer) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { autoAdvanceProgress.value },
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .fillMaxWidth(.55f)
                                    .height(8.dp),
                                color = Gold,
                                trackColor = Ink.copy(alpha = .10f)
                            )
                        } else {
                            Text("Reviewing your previous answer", color = Ink.copy(alpha = .65f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.hints.isNotEmpty()) {
                FocusActionButton(
                    label = if (revealedHintCount < item.hints.size) {
                        "Give me a hint"
                    } else {
                        "All hints shown"
                    },
                    onClick = onRevealHint,
                    enabled = chosen == null && revealedHintCount < item.hints.size,
                    modifier = Modifier.weight(1.2f).heightIn(min = 60.dp),
                    primary = false
                )
            }
            FocusActionButton(
                label = if (number == total) "Skip & finish" else "Skip",
                onClick = onNext,
                enabled = chosen == null,
                modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                primary = false
            )
            FocusActionButton(
                label = if (number == total) "See results" else "Next question →",
                onClick = onNext,
                enabled = chosen != null,
                modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                primary = true
            )
        }
    }
}

@Composable
private fun ExampleSourcePicker(
    language: Language,
    selected: ExampleSource,
    onSelected: (ExampleSource) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember(language) { ExampleSource.forLanguage(language) }
    Box(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
        FocusActionButton(
            label = "${selected.icon} Search source: ${selected.label}  ▾",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            primary = false
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp)
        ) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${source.icon}  ${source.label}${if (source == selected) "  ✓" else ""}",
                            fontWeight = if (source == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(source)
                    }
                )
            }
        }
    }
}

private fun answerChoicesFor(item: QuizItem): List<String> {
    val answer = item.answer
    return when {
        answer in listOf("nominative", "accusative", "dative", "genitive") ->
            listOf("nominative", "accusative", "dative", "genitive")

        answer in listOf("wo?", "wohin?", "woher?", "wann?") ->
            listOf("wo?", "wohin?", "woher?", "wann?")

        answer in listOf("mich", "dich", "ihn", "sie", "es", "uns", "euch") ->
            listOf("mich", "dich", "ihn", "sie", "uns", "euch")

        answer in listOf("mir", "dir", "ihm", "ihr", "ihnen", "Ihnen") ->
            listOf("mir", "dir", "ihm", "ihr", "uns", "euch", "ihnen")

        answer in listOf("beim", "vom", "zum", "zur") ->
            listOf("beim", "vom", "zum", "zur")

        item.language == Language.GERMAN && answer in
            listOf("den", "dem", "die", "der", "das", "dein", "einen", "deinem") ->
            listOf("der", "die", "das", "den", "dem", "einen", "dein", "deinem")

        item.language == Language.FRENCH && item.category == QuizCategory.ARTICLES &&
            answer in listOf("le", "la", "l'", "les") -> listOf("le", "la", "l'", "les")

        answer in listOf("nous", "tu", "vous", "je", "il", "elle", "ils", "elles") ->
            listOf("je", "tu", "il", "elle", "nous", "vous", "ils", "elles")

        answer in listOf("suis", "es", "est", "sommes", "êtes", "sont") ->
            listOf("suis", "es", "est", "sommes", "êtes", "sont")

        answer in listOf("ai", "as", "a", "avons", "avez", "ont") ->
            listOf("ai", "as", "a", "avons", "avez", "ont")

        answer in listOf("du", "au", "de la", "à la") ->
            listOf("du", "au", "de la", "à la")

        item.language == Language.FRENCH && item.category == QuizCategory.GRAMMAR ->
            listOf("parle", "parlons", "prends", "allons", "J'aime", answer).distinct()

        else -> QuizData.items
            .filter { it.language == item.language && it.category == item.category && (it.variant == null || it.variant == item.variant) }
            .map { it.answer }
            .distinct()
    }
}

@Composable
private fun AnswerOptionButton(
    option: String,
    correctAnswer: String,
    chosen: String?,
    answerMarker: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val selected = option == chosen
    val correct = option == correctAnswer
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "answer focus")
    val background = when {
        chosen != null && correct -> Green
        chosen != null && selected -> Red
        else -> Ink.copy(alpha = .06f)
    }
    val foreground = if (chosen != null && (correct || selected)) Color.White else Ink
    val outline = when {
        selected -> Gold
        focused -> Gold
        else -> Ink.copy(alpha = .25f)
    }
    val visualAnswer = if (answerMarker.isNotBlank()) "$answerMarker  $option" else option

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .heightIn(min = 58.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected || focused) 4.dp else 1.dp, outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = background,
            contentColor = foreground
        )
    ) {
        Text(
            "${if (focused && chosen == null) "▶  " else ""}$visualAnswer${if (selected) "  ✓" else ""}",
            fontSize = if (selected || focused) 20.sp else 18.sp,
            fontWeight = if (selected) FontWeight.Black else if (focused) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

private fun answerMarker(item: QuizItem, option: String): String = when (item.category) {
    QuizCategory.VOCABULARY -> emojiForAnswer(option)
    QuizCategory.ARTICLES -> ""
    QuizCategory.GRAMMAR -> ""
}

private fun emojiForAnswer(answer: String): String = when (answer.lowercase()) {
    "who" -> "👤"
    "hello / good morning" -> "👋"
    "goodbye" -> "👋"
    "please" -> "🙏"
    "thank you" -> "💛"
    "in a good mood", "happy" -> "😊"
    "neat / orderly / clean", "to clean" -> "🧹"
    "important" -> "❗"
    "almost" -> "⌛"
    "mean" -> "😠"
    "probably", "maybe" -> "🤔"
    "safe / sure" -> "🛡️"
    "dangerous" -> "⚠️"
    "straight ahead" -> "⬆️"
    "up ahead" -> "👆"
    "closed" -> "🔒"
    "over there" -> "👉"
    "not at all", "never" -> "🚫"
    "boring" -> "🥱"
    "tired" -> "😴"
    "far away" -> "🔭"
    "empty" -> "🫙"
    "no idea" -> "🤷"
    "for the first time" -> "1️⃣"
    "around the corner" -> "↪️"
    "on time" -> "⏰"
    "enough" -> "✅"
    "hard / difficult / heavy", "difficult" -> "🏋️"
    "easy" -> "👌"
    "right now", "now" -> "⏱️"
    "tomorrow" -> "➡️"
    "yesterday" -> "⬅️"
    "always" -> "♾️"
    "less / fewer" -> "➖"
    "cheap" -> "🏷️"
    "nice / kind" -> "🙂"
    "don't worry" -> "😌"
    "finished" -> "🏁"
    "again" -> "🔁"
    "to take" -> "✋"
    "to share / divide" -> "🤝"
    "to pay" -> "💳"
    "to disturb / bother" -> "🔔"
    "to click" -> "🖱️"
    "to visit" -> "🧳"
    "to tour / sightsee" -> "🏛️"
    "to go for a walk" -> "🚶"
    "to fetch / go get", "to look for" -> "🔎"
    "to find" -> "💡"
    "to take along" -> "🎒"
    "to count" -> "🔢"
    "to hear", "to listen" -> "👂"
    "to say", "to speak" -> "💬"
    "to stand" -> "🧍"
    "to stop" -> "🛑"
    "to become" -> "🔄"
    "snack" -> "🍿"
    "mid-morning snack (swiss german)", "afternoon snack (swiss german)" -> "🍪"
    "snack bar / takeaway stand" -> "🌭"
    "pen" -> "🖊️"
    "electric bicycle / e-bike" -> "🚲"
    "hospital", "hospital (swiss german; krankenhaus in germany)" -> "🏥"
    else -> "💬"
}

@Composable
private fun ResultsScreen(score: Int, total: Int, bestStreak: Int, language: Language, onAgain: () -> Unit, onHome: () -> Unit) {
    val percent = score * 100 / total
    val playAgainFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playAgainFocusRequester.requestFocus()
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val message = if (language == Language.GERMAN) {
            if (percent >= 80) {
                "Ausgezeichnet! · Excellent!"
            } else if (percent >= 60) {
                "Gut gemacht! · Well done!"
            } else {
                "Weiter üben! · Keep practicing!"
            }
        } else {
            if (percent >= 80) "Excellent !" else if (percent >= 60) "Bien joué !" else "Continuez !"
        }
        Text(message, fontSize = 38.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("$score / $total", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Green)
        Text("$percent% correct  •  Best streak: $bestStreak", fontSize = 18.sp)
        Spacer(Modifier.height(32.dp))
        FocusActionButton(
            label = "Play again",
            onClick = onAgain,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .focusRequester(playAgainFocusRequester),
            primary = true
        )
        Spacer(Modifier.height(12.dp))
        FocusActionButton(
            label = "Choose another category",
            onClick = onHome,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            primary = false
        )
    }
}

@Composable
private fun FocusActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "$label focus")
    val styledModifier = modifier
        .scale(scale)
        .onFocusChanged { focused = it.isFocused }
    val displayedLabel = "${if (focused) "▶  " else ""}$label"

    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = styledModifier,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (focused) 5.dp else 0.dp, if (focused) Gold else Color.Transparent)
        ) {
            Text(
                displayedLabel,
                fontSize = if (focused) 20.sp else 18.sp,
                fontWeight = if (focused) FontWeight.Black else FontWeight.Bold
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = styledModifier,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (focused) 5.dp else 2.dp, if (focused) Gold else Green)
        ) {
            Text(
                displayedLabel,
                fontSize = if (focused) 20.sp else 18.sp,
                fontWeight = if (focused) FontWeight.Black else FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AgentMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    color: Color = Ink
) {
    val styled = remember(markdown) { markdown.toInlineMarkdownAnnotatedString() }
    Text(
        text = styled,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        textAlign = TextAlign.Center
    )
}

/** Lightweight markdown for short model replies (**bold**, *italic*, `code`, [links](url), ```fences```). */
private fun String.toInlineMarkdownAnnotatedString(): AnnotatedString {
    val normalized = trim()
        // Drop fenced code blocks but keep their inner text (often a URL).
        .replace(Regex("""```[a-zA-Z0-9_-]*\s*\n?(.*?)```""", RegexOption.DOT_MATCHES_ALL)) { match ->
            match.groupValues[1].trim()
        }
        // Models often append a redundant URL block; the Open button already covers that.
        .replace(Regex("""(?im)^\s*Exact item URL:?\s*\n?.*$"""), "")
        .replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""(?m)^#{1,6}\s+"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .replace("()", "")
        .replace("[]", "")
        .trim()
    return buildAnnotatedString {
        val pattern = Regex(
            """(\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|\*(.+?)\*|_(.+?)_|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\))""",
            RegexOption.DOT_MATCHES_ALL
        )
        var cursor = 0
        for (match in pattern.findAll(normalized)) {
            append(normalized.substring(cursor, match.range.first))
            when {
                match.groupValues[2].isNotEmpty() -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                ) { append(match.groupValues[2]) }
                match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[3])
                }
                match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[4])
                }
                match.groupValues[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[5])
                }
                match.groupValues[6].isNotEmpty() -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = Ink.copy(alpha = 0.08f))
                ) { append(match.groupValues[6]) }
                match.groupValues[7].isNotEmpty() -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, color = Green)
                ) { append(match.groupValues[7]) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < normalized.length) append(normalized.substring(cursor))
    }
}
