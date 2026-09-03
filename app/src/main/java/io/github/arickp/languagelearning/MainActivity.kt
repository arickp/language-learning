package io.github.arickp.languagelearning

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
        null -> Box(
            Modifier.fillMaxSize().background(Cream),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppLogo(size = 220.dp)
                Spacer(Modifier.height(28.dp))
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

@Composable
private fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher),
        contentDescription = "Language Learning",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 12))
    )
}

private enum class Screen { HOME, SETTINGS, TRIP_QUIZ, QUIZ, RESULTS }

/** Stored value for the "all skill levels" difficulty selection, represented in code as null. */
private const val MIXED_DIFFICULTY = "MIXED"

private val difficultyOptions: List<Difficulty?> = listOf(null) + Difficulty.entries

private val Difficulty?.selectionLabel: String
    get() = this?.label ?: "Mixed"

private val Difficulty?.selectionDescription: String
    get() = this?.description ?: "All skill levels, from beginner basics to specialist terms"

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
            when (val stored = preferences.getString("difficulty", Difficulty.EASY.name)) {
                MIXED_DIFFICULTY -> null
                else -> runCatching { Difficulty.valueOf(stored!!) }.getOrDefault(Difficulty.EASY)
            }
        )
    }
    var selectedOrder by remember {
        mutableStateOf(
            runCatching { QuestionOrder.valueOf(preferences.getString("question_order", QuestionOrder.MIXED.name)!!) }
                .getOrDefault(QuestionOrder.MIXED)
        )
    }
    var selectedQuestionCount by remember {
        mutableIntStateOf(preferences.getInt("question_count", 10).coerceIn(5, 100))
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
    var tripQuestions by remember { mutableStateOf<List<QuizItem>?>(null) }
    var regeneratingTripQuiz by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun start(variant: LanguageVariant, category: QuizCategory?) {
        tripQuestions = null
        selectedVariant = variant
        val language = variant.language
        selectedCategory = category
        val matching = QuizData.items.filter {
            it.language == language &&
                it.appliesTo(variant) &&
                (category == null || it.category == category) &&
                (allowExplicitContent || !it.explicit)
        }
        val difficulty = selectedDifficulty
        val pool = if (selectedOrder == QuestionOrder.NEWEST || difficulty == null) {
            matching
        } else {
            val exactPool = matching.filter { it.difficulty == difficulty }
            if (exactPool.size >= selectedQuestionCount) {
                exactPool
            } else {
                val supplementalPool = matching
                    .filter { it.difficulty != difficulty }
                    .sortedBy { kotlin.math.abs(it.difficulty.ordinal - difficulty.ordinal) }
                exactPool + supplementalPool.take(selectedQuestionCount - exactPool.size)
            }
        }
        if (pool.size < selectedQuestionCount) {
            val label = category?.label?.lowercase() ?: "mixed"
            startError =
                "Only ${pool.size} $label questions are available for ${variant.label}; choose ${pool.size.coerceAtLeast(5)} or fewer."
            return
        }
        val recentKeySet = recentQuestionKeys.toSet()
        questions = orderedQuestions(pool, recentQuestionKeys, recentKeySet, selectedOrder)
            .take(selectedQuestionCount)
        if (questions.isEmpty()) {
            val label = category?.label?.lowercase() ?: "mixed"
            startError = "No $label questions for ${variant.label} · ${selectedDifficulty.selectionLabel}."
            return
        }
        startError = null
        recentQuestionKeys = (recentQuestionKeys + questions.map { it.historyKey() })
            .takeLast(maxOf(30, selectedQuestionCount * 3))
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
            Screen.TRIP_QUIZ -> screen = Screen.HOME
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
                    preferences.edit()
                        .putString("difficulty", difficulty?.name ?: MIXED_DIFFICULTY)
                        .apply()
                },
                order = selectedOrder,
                onOrderChange = { order ->
                    selectedOrder = order
                    preferences.edit().putString("question_order", order.name).apply()
                },
                questionCount = selectedQuestionCount,
                onQuestionCountChange = { count ->
                    selectedQuestionCount = count
                    preferences.edit().putInt("question_count", count).apply()
                },
                onStart = ::start,
                onTripQuiz = { screen = Screen.TRIP_QUIZ },
                onSettings = { screen = Screen.SETTINGS },
                startError = startError
            )
            Screen.SETTINGS -> SettingsScreen(
                allowExplicitContent = allowExplicitContent,
                onSave = { allowed ->
                    allowExplicitContent = allowed
                    preferences.edit().putBoolean("allow_explicit_content", allowed).apply()
                    screen = Screen.HOME
                },
                onBack = { screen = Screen.HOME }
            )
            Screen.TRIP_QUIZ -> TripQuizScreen(
                variant = selectedVariant,
                questionCount = selectedQuestionCount,
                allowExplicitContent = allowExplicitContent,
                onBack = { screen = Screen.HOME },
                onStart = { generated ->
                    tripQuestions = generated.questions
                    selectedCategory = QuizCategory.VOCABULARY
                    questions = generated.questions
                    index = 0
                    score = 0
                    streak = 0
                    bestStreak = 0
                    answeredChoices = emptyMap()
                    revealedHintCounts = emptyMap()
                    exampleStates = emptyMap()
                    exampleSources = emptyMap()
                    startError = null
                    screen = Screen.QUIZ
                }
            )
            Screen.QUIZ -> {
                val item = questions.getOrNull(index)
                if (item != null) {
                    QuizScreen(
                        item = item, number = index + 1, total = questions.size,
                        variant = selectedVariant,
                        allowExplicitContent = allowExplicitContent,
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
                regenerating = regeneratingTripQuiz,
                onAgain = {
                    val generated = tripQuestions
                    if (generated == null) {
                        start(selectedVariant, selectedCategory)
                    } else {
                        val savedSource = preferences.getString("trip_quiz_source", "").orEmpty()
                        regeneratingTripQuiz = true
                        coroutineScope.launch {
                            val fresh = if (savedSource.isBlank()) {
                                null
                            } else {
                                TripQuizGenerator.generate(
                                    source = savedSource,
                                    variant = selectedVariant,
                                    questionCount = selectedQuestionCount,
                                    allowExplicit = allowExplicitContent
                                ).getOrNull()
                            }
                            // Fresh AI questions when possible; otherwise reshuffle the old set.
                            val nextQuestions = fresh?.questions ?: generated.shuffled()
                            if (fresh != null) tripQuestions = fresh.questions
                            questions = nextQuestions
                            index = 0
                            score = 0
                            streak = 0
                            bestStreak = 0
                            answeredChoices = emptyMap()
                            revealedHintCounts = emptyMap()
                            exampleStates = emptyMap()
                            exampleSources = emptyMap()
                            regeneratingTripQuiz = false
                            screen = Screen.QUIZ
                        }
                    }
                },
                onHome = { screen = Screen.HOME }
            )
        }
    }
}

private fun orderedQuestions(
    pool: List<QuizItem>,
    recentQuestionKeys: List<String>,
    recentKeySet: Set<String>,
    order: QuestionOrder
): List<QuizItem> {
    if (order == QuestionOrder.NEWEST) {
        return pool
            .groupBy { it.dateAdded.orEmpty() }
            .toList()
            .sortedByDescending { it.first }
            .flatMap { (_, items) ->
                val notRecentlySeen = items.filter { it.historyKey() !in recentKeySet }.shuffled()
                val recentlySeen = items
                    .filter { it.historyKey() in recentKeySet }
                    .shuffled()
                    .sortedBy { recentQuestionKeys.lastIndexOf(it.historyKey()) }
                notRecentlySeen + recentlySeen
            }
    }
    val notRecentlySeen = pool.filter { it.historyKey() !in recentKeySet }.shuffled()
    val recentlySeen = pool
        .filter { it.historyKey() in recentKeySet }
        .shuffled()
        .sortedBy { recentQuestionKeys.lastIndexOf(it.historyKey()) }
    return notRecentlySeen + recentlySeen
}

private fun QuizItem.historyKey(): String = "${language.name}|${category.name}|$prompt"

@Composable
private fun HomeScreen(
    variant: LanguageVariant,
    onVariantChange: (LanguageVariant) -> Unit,
    difficulty: Difficulty?,
    onDifficultyChange: (Difficulty?) -> Unit,
    order: QuestionOrder,
    onOrderChange: (QuestionOrder) -> Unit,
    questionCount: Int,
    onQuestionCountChange: (Int) -> Unit,
    onStart: (LanguageVariant, QuizCategory?) -> Unit,
    onTripQuiz: () -> Unit,
    onSettings: () -> Unit,
    startError: String? = null
) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var difficultyMenuExpanded by remember { mutableStateOf(false) }
    var orderMenuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.widthIn(max = 860.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppLogo(size = 132.dp)
        Spacer(Modifier.height(16.dp))
        Text("LANGUAGE LEARNING", color = Green, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text("Train your ${variant.language.label}", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Text(
            if (order == QuestionOrder.NEWEST) {
                "$questionCount questions. Instant feedback. Newest words first."
            } else {
                "$questionCount questions. Instant feedback. A fresh mix every time."
            },
            fontSize = 18.sp,
            color = Ink.copy(alpha = .7f),
            textAlign = TextAlign.Center
        )
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
                label = "Difficulty: ${difficulty.selectionLabel}  ▾",
                onClick = { difficultyMenuExpanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                primary = false
            )
            DropdownMenu(
                expanded = difficultyMenuExpanded,
                onDismissRequest = { difficultyMenuExpanded = false },
                modifier = Modifier.widthIn(min = 320.dp, max = 620.dp)
            ) {
                difficultyOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${option.selectionLabel}${if (option == difficulty) "  ✓" else ""}",
                                    fontSize = 17.sp,
                                    fontWeight = if (option == difficulty) FontWeight.Black else FontWeight.Bold
                                )
                                Text(option.selectionDescription, fontSize = 13.sp, color = Ink.copy(alpha = .66f))
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
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth()) {
            FocusActionButton(
                label = "Order: ${order.label}  ▾",
                onClick = { orderMenuExpanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                primary = false
            )
            DropdownMenu(
                expanded = orderMenuExpanded,
                onDismissRequest = { orderMenuExpanded = false },
                modifier = Modifier.widthIn(min = 320.dp, max = 620.dp)
            ) {
                QuestionOrder.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${option.label}${if (option == order) "  ✓" else ""}",
                                    fontSize = 17.sp,
                                    fontWeight = if (option == order) FontWeight.Black else FontWeight.Bold
                                )
                                Text(option.description, fontSize = 13.sp, color = Ink.copy(alpha = .66f))
                            }
                        },
                        onClick = {
                            onOrderChange(option)
                            orderMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Questions per quiz: $questionCount",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { onQuestionCountChange((questionCount - 1).coerceAtLeast(5)) },
                enabled = questionCount > 5
            ) {
                Text("−", fontSize = 24.sp)
            }
            Slider(
                value = questionCount.toFloat(),
                onValueChange = {
                    onQuestionCountChange(it.roundToInt().coerceIn(5, 100))
                },
                valueRange = 5f..100f,
                steps = 94,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            OutlinedButton(
                onClick = { onQuestionCountChange((questionCount + 1).coerceAtMost(100)) },
                enabled = questionCount < 100
            ) {
                Text("+", fontSize = 24.sp)
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
        CategoryButton(
            "Prepare me for my trip",
            "Paste an itinerary or public link to generate a custom quiz",
            Color(0xFFB45F45),
            onTripQuiz
        )
        }
    }
}

@Composable
private fun TripQuizScreen(
    variant: LanguageVariant,
    questionCount: Int,
    allowExplicitContent: Boolean,
    onBack: () -> Unit,
    onStart: (GeneratedTripQuiz) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("language_learning_settings", 0) }
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    var source by remember { mutableStateOf(preferences.getString("trip_quiz_source", "").orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingSession by remember { mutableStateOf<ItineraryPairingSession?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingMessage by remember { mutableStateOf<String?>(null) }
    var pairingAttempt by remember { mutableIntStateOf(0) }
    var showRemoteInput by remember(isTv) {
        mutableStateOf(showRemoteItineraryInputByDefault(isTv))
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isTv, pairingAttempt) {
        if (!isTv || pairingSession != null) return@LaunchedEffect
        pairingBusy = true
        pairingMessage = null
        error = null
        runCatching { TripItineraryPairing.createSession() }
            .onSuccess { pairingSession = it }
            .onFailure { error = it.message ?: "Could not create an itinerary QR session." }
        pairingBusy = false
    }

    LaunchedEffect(pairingSession?.token) {
        val session = pairingSession ?: return@LaunchedEffect
        while (pairingSession?.token == session.token) {
            delay(2_000)
            val result = runCatching { TripItineraryPairing.pollItinerary(session.token) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                pairingSession = null
                error = failure.message ?: "Could not receive the itinerary."
                break
            }
            val itinerary = result.getOrNull()
            if (shouldAutoStartTripQuiz(itinerary)) {
                val receivedItinerary = itinerary.orEmpty()
                source = receivedItinerary
                preferences.edit().putString("trip_quiz_source", receivedItinerary).apply()
                pairingMessage = "Itinerary received. Building your trip quiz…"
                error = null
                loading = true
                TripQuizGenerator.generate(
                    source = receivedItinerary,
                    variant = variant,
                    questionCount = questionCount,
                    allowExplicit = allowExplicitContent
                ).fold(
                    onSuccess = onStart,
                    onFailure = {
                        error = it.message ?: "Could not generate the trip quiz."
                        loading = false
                    }
                )
                break
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 760.dp)) {
            TextButton(onClick = onBack, enabled = !loading) { Text("← Back") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Prepare me for my trip",
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                color = Ink
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Paste itinerary text or a public Google Doc or website link. AI will create $questionCount practical ${variant.language.label} questions for ${variant.speechRegion}.",
                fontSize = 19.sp,
                lineHeight = 27.sp,
                color = Ink.copy(alpha = .78f)
            )
            Spacer(Modifier.height(20.dp))
            if (isTv) {
                val session = pairingSession
                if (session == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pairingBusy) {
                            CircularProgressIndicator(Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Creating QR code…", fontWeight = FontWeight.Bold)
                        } else {
                            TextButton(onClick = { pairingAttempt++ }) { Text("Retry QR code") }
                        }
                    }
                } else {
                    val formUrl = remember(session.token) {
                        TripItineraryPairing.formUrl(BuildConfig.SERVER_URL, session.token)
                    }
                    val qrCode = remember(formUrl) {
                        TripItineraryPairing.qrBitmap(formUrl).asImageBitmap()
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(18.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Scan with your phone",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = Ink
                        )
                        Spacer(Modifier.height(10.dp))
                        Image(
                            bitmap = qrCode,
                            contentDescription = "QR code for entering the travel itinerary",
                            modifier = Modifier.size(250.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The link expires in ${session.expiresInSeconds / 60} minutes. This screen updates after you submit.",
                            textAlign = TextAlign.Center,
                            color = Ink.copy(alpha = .72f)
                        )
                    }
                }
                pairingMessage?.let {
                    Text(
                        it,
                        color = Green,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                TextButton(
                    onClick = { showRemoteInput = !showRemoteInput },
                    enabled = !loading,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (showRemoteInput) "Hide TV remote entry" else "Or enter using my TV remote…",
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (showRemoteInput) {
                OutlinedTextField(
                    value = source,
                    onValueChange = {
                        source = it
                        error = null
                        preferences.edit().putString("trip_quiz_source", it).apply()
                    },
                    label = { Text("Itinerary text or public link") },
                    placeholder = {
                        Text("Flight, hotel, cities, activities…\nor https://docs.google.com/document/d/…")
                    },
                    minLines = 8,
                    maxLines = 16,
                    enabled = !loading,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (source.isNotEmpty() && !loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = {
                                source = ""
                                error = null
                                preferences.edit().remove("trip_quiz_source").apply()
                            }
                        ) { Text("Clear") }
                    }
                }
            }
            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Building your trip quiz…", fontWeight = FontWeight.Bold)
                }
            } else {
                FocusActionButton(
                    label = "Generate $questionCount questions",
                    onClick = {
                        if (source.isBlank()) {
                            error = "Paste itinerary text or a public link first."
                        } else {
                            loading = true
                            error = null
                            scope.launch {
                                TripQuizGenerator.generate(
                                    source = source,
                                    variant = variant,
                                    questionCount = questionCount,
                                    allowExplicit = allowExplicitContent
                                ).fold(
                                    onSuccess = onStart,
                                    onFailure = {
                                        error = it.message ?: "Could not generate the trip quiz."
                                        loading = false
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
                    primary = true
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Only public HTTP or HTTPS pages are supported. Private-network links and pages larger than 1 MB are rejected.",
                fontSize = 13.sp,
                color = Ink.copy(alpha = .62f)
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    allowExplicitContent: Boolean,
    onSave: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draftAllowExplicitContent by remember(allowExplicitContent) {
        mutableStateOf(allowExplicitContent)
    }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    val hasUnsavedChanges = draftAllowExplicitContent != allowExplicitContent
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
                "This app can include sexually explicit or vulgar vocabulary, and it can search the Internet for posts using the words you learn. Blocking is the default.",
                fontSize = 21.sp,
                lineHeight = 29.sp,
                color = Ink
            )
            Spacer(Modifier.height(24.dp))
            SettingChoice(
                title = "Block explicit content",
                subtitle = "Recommended · Hide explicit quiz words and skip NSFW examples",
                selected = !draftAllowExplicitContent,
                onClick = { draftAllowExplicitContent = false }
            )
            Spacer(Modifier.height(12.dp))
            SettingChoice(
                title = "Allow explicit content",
                subtitle = "Vulgar or sexual vocabulary and NSFW posts may appear and will be labelled when possible",
                selected = draftAllowExplicitContent,
                onClick = { draftAllowExplicitContent = true }
            )
            Spacer(Modifier.height(28.dp))
            Text(
                if (hasUnsavedChanges) "You have unsaved changes." else "All changes saved.",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasUnsavedChanges) Red else Ink.copy(alpha = .55f)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusActionButton(
                    label = "Save",
                    onClick = { onSave(draftAllowExplicitContent) },
                    enabled = hasUnsavedChanges,
                    modifier = Modifier.widthIn(max = 260.dp).weight(1f).heightIn(min = 58.dp),
                    primary = true
                )
                Spacer(Modifier.width(14.dp))
                FocusActionButton(
                    label = "Discard",
                    onClick = { draftAllowExplicitContent = allowExplicitContent },
                    enabled = hasUnsavedChanges,
                    modifier = Modifier.widthIn(max = 260.dp).weight(1f).heightIn(min = 58.dp),
                    primary = false
                )
            }
            Spacer(Modifier.height(36.dp))
            Text("Local cache", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "Clears the saved word bank and downloaded pronunciations, then reloads from the companion server if it is reachable.",
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = Ink.copy(alpha = .78f)
            )
            Spacer(Modifier.height(14.dp))
            FocusActionButton(
                label = if (clearingCache) "Clearing…" else "Clear cache",
                onClick = {
                    if (clearingCache) return@FocusActionButton
                    clearingCache = true
                    cacheMessage = null
                    scope.launch {
                        QuizData.clearLocalCaches(context)
                        cacheMessage = QuizData.load(context).fold(
                            onSuccess = {
                                "Cache cleared. The word bank was reloaded from the server."
                            },
                            onFailure = { error ->
                                "Cache files were deleted. The word bank could not be reloaded: ${error.message ?: "server unavailable"}"
                            }
                        )
                        clearingCache = false
                    }
                },
                enabled = !clearingCache,
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                primary = false
            )
            if (cacheMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    cacheMessage!!,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
            }
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
    allowExplicitContent: Boolean,
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
    val firstAnswerFocusRequester = remember(item) { FocusRequester() }
    val nextButtonFocusRequester = remember(item) { FocusRequester() }
    val feedbackBringIntoViewRequester = remember(item) { BringIntoViewRequester() }
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
    val categoryPool = answerChoicesFor(item, allowExplicitContent)
    val options = remember(item) {
        (categoryPool.filter { it != item.answer }.shuffled().take(3) + item.answer).shuffled()
    }
    // Compact layout on Android TV: shrink chrome so all answer options fit without scrolling.
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION

    LaunchedEffect(item) {
        questionScrollState.scrollTo(0)
        if (initialQuizAnswerIndex(isTv, options.size) != null) {
            runCatching { firstAnswerFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(chosen) {
        if (chosen != null) {
            if (shouldAutoRevealQuizFeedback(isTv, hasAnswer = true)) {
                delay(250)
                feedbackBringIntoViewRequester.bringIntoView()
            }
            // Move D-pad/remote focus to "Next question" so a single OK press advances (Android TV).
            runCatching { nextButtonFocusRequester.requestFocus() }
        }
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

    if (quizPaneCount(isTv) == 2) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(12.dp)
                .widthIn(max = 1100.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onQuit) { Text("← Quit") }
                LinearProgressIndicator(
                    progress = { number.toFloat() / total },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = Gold,
                    trackColor = Ink.copy(alpha = .1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("$number / $total", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(16.dp))
                Text(
                    "${item.category.label.uppercase()} · ${item.difficulty.label.uppercase()}",
                    color = Green,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(16.dp))
                Text("Score $score   🔥 $streak", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1.8f).fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(14.dp).verticalScroll(questionScrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            item.prompt,
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.focusable()
                        )
                        item.translation?.let { translation ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                translation,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.copy(alpha = .68f),
                                textAlign = TextAlign.Center
                            )
                        }
                        AnimatedVisibility(revealedHintCount > 0) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 8.dp)
                                    .background(Gold.copy(alpha = .13f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                item.hints.take(revealedHintCount).forEachIndexed { hintIndex, hint ->
                                    Text(
                                        "Hint ${hintIndex + 1}: $hint",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        options.forEachIndexed { index, option ->
                            AnswerOptionButton(
                                option = option,
                                correctAnswer = item.answer,
                                chosen = chosen,
                                answerMarker = answerMarker(item, option),
                                modifier = if (index == initialQuizAnswerIndex(isTv, options.size)) {
                                    Modifier.focusRequester(firstAnswerFocusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    if (chosen == null) {
                                        onChoose(option)
                                        autoAdvanceThisAnswer = true
                                        onAnswer(option == item.answer)
                                    }
                                }
                            )
                        }
                    }
                }
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (chosen == null) {
                                Text(
                                    "Choose an answer",
                                    color = Ink.copy(alpha = .58f),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                val success = if (item.language == Language.GERMAN) {
                                    "Richtig! · Right ✓"
                                } else {
                                    "Correct! ✓"
                                }
                                Text(
                                    if (chosen == item.answer) success else "Not quite",
                                    color = if (chosen == item.answer) Green else Red,
                                    fontSize = 26.sp,
                                    lineHeight = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(item.explanation, fontSize = 16.sp, textAlign = TextAlign.Center)
                                if (autoAdvanceThisAnswer) {
                                    Spacer(Modifier.height(14.dp))
                                    LinearProgressIndicator(
                                        progress = { autoAdvanceProgress.value },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = Gold,
                                        trackColor = Ink.copy(alpha = .10f)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (chosen == null) {
                            if (item.hints.isNotEmpty()) {
                                FocusActionButton(
                                    label = if (revealedHintCount < item.hints.size) "Give me a hint" else "All hints shown",
                                    onClick = onRevealHint,
                                    enabled = revealedHintCount < item.hints.size,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    primary = false
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            FocusActionButton(
                                label = if (number == total) "Skip & finish" else "Skip",
                                onClick = onNext,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                primary = false
                            )
                        } else {
                            FocusActionButton(
                                label = if (number == total) "See results" else "Next question →",
                                onClick = onNext,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .focusRequester(nextButtonFocusRequester),
                                primary = true
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(if (isTv) 12.dp else 24.dp)
            .widthIn(max = 1100.dp)
    ) {
        if (isTv) {
            // Single compact header row on TV: quit, progress, count, category, score.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onQuit) { Text("← Quit") }
                LinearProgressIndicator(
                    progress = { number.toFloat() / total },
                    modifier = Modifier.weight(1f).height(6.dp), color = Gold, trackColor = Ink.copy(alpha = .1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("$number / $total", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(16.dp))
                Text(
                    "${item.category.label.uppercase()} · ${item.difficulty.label.uppercase()}",
                    color = Green, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp
                )
                Spacer(Modifier.width(16.dp))
                Text("Score $score   🔥 $streak", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        } else {
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
        }
        Card(
            Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(if (isTv) 20.dp else 28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.fillMaxSize().padding(if (isTv) 14.dp else 28.dp).verticalScroll(questionScrollState),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text(
                    item.prompt,
                    fontSize = if (isTv) 24.sp else 30.sp,
                    lineHeight = if (isTv) 30.sp else 38.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    // A focus target above the answers lets TV remotes navigate back up and
                    // causes the scroll container to bring the question into view again.
                    modifier = Modifier.focusable(enabled = isTv)
                )
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
                    Column(
                        Modifier
                            .bringIntoViewRequester(feedbackBringIntoViewRequester)
                            .padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                                ExampleDiscoveryState.Loading -> Unit
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
                        val aiWorking = exampleState is ExampleDiscoveryState.Loading ||
                            practiceBusy ||
                            pronunciationState is PronunciationState.Loading
                        when {
                            autoAdvanceThisAnswer -> {
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
                            }
                            aiWorking -> {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Green)
                                    Text("Working", color = Ink.copy(alpha = .65f), fontSize = 14.sp)
                                }
                            }
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
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .focusRequester(nextButtonFocusRequester),
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

private fun answerChoicesFor(item: QuizItem, allowExplicitContent: Boolean): List<String> {
    val answer = item.answer
    if (item.distractors.isNotEmpty()) return item.distractors + answer
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

        answer in listOf("nach", "zu", "in", "an", "um") ->
            listOf("nach", "zu", "in", "an", "um")

        answer in listOf("dieser", "diese", "dieses") ->
            listOf("dieser", "diese", "dieses")

        answer == "DOGFU" ->
            listOf("DOGFU", "WAGEN", "SDOP", "CANS")

        answer in listOf("in order to", "because", "instead of", "without") ->
            listOf("in order to", "because", "instead of", "without")

        answer in listOf("um … zu", "zu … um", "für zu", "damit") ->
            listOf("um … zu", "zu … um", "für zu", "damit")

        answer in listOf("anzurufen", "zu anrufen", "anrufen zu", "um anrufen") ->
            listOf("anzurufen", "zu anrufen", "anrufen zu", "um anrufen")

        // "Choose the article" is about noun gender — keep it to the three nominative articles.
        item.language == Language.GERMAN && item.category == QuizCategory.ARTICLES &&
            answer in listOf("der", "die", "das") -> listOf("der", "die", "das")

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
            .filter {
                it.language == item.language &&
                    it.category == item.category &&
                    (it.variant == null || it.variant == item.variant) &&
                    (allowExplicitContent || !it.explicit)
            }
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
    modifier: Modifier = Modifier,
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
        modifier = modifier
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

private const val GENERIC_ANSWER_MARKER = "💬"

private fun answerMarker(item: QuizItem, option: String): String {
    // German gender colors: blue = masculine (der), red = feminine (die), green = neuter (das).
    if (item.language == Language.GERMAN && item.category == QuizCategory.ARTICLES) {
        return when (option) {
            "der" -> "🔵"
            "die" -> "🔴"
            "das" -> "🟢"
            else -> ""
        }
    }
    if (item.category != QuizCategory.VOCABULARY) return ""
    // Generated questions carry an emoji for the correct answer only, which would give it away.
    if (item.distractors.isNotEmpty()) return ""
    if (option == item.answer) return item.emoji ?: GENERIC_ANSWER_MARKER
    return QuizData.items.firstOrNull {
        it.language == item.language &&
            it.category == QuizCategory.VOCABULARY &&
            it.answer.equals(option, ignoreCase = true) &&
            !it.emoji.isNullOrBlank()
    }?.emoji ?: GENERIC_ANSWER_MARKER
}

@Composable
private fun ResultsScreen(score: Int, total: Int, bestStreak: Int, language: Language, regenerating: Boolean = false, onAgain: () -> Unit, onHome: () -> Unit) {
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
        if (regenerating) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Generating fresh questions…", fontWeight = FontWeight.Bold)
            }
        } else {
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
