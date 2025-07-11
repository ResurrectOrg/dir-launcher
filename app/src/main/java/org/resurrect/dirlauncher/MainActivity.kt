package org.resurrect.dirlauncher

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import androidx.core.content.edit

data class AppInfo(
    val label: CharSequence,
    val packageName: CharSequence,
    val iconBitmap: ImageBitmap
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val _categorizedApps = MutableStateFlow<Map<String, List<AppInfo>>>(emptyMap())
    val categorizedApps: StateFlow<Map<String, List<AppInfo>>> = _categorizedApps

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    //    API | CACHE
    private val _fetchType = MutableStateFlow("API")
    val fetchType: StateFlow<String> = _fetchType

    private val _pinnedKey = "PinnedApps"
    private val _pinnedApps = MutableStateFlow<Set<String>>(emptySet())
    val pinnedApps: StateFlow<Set<String>> = _pinnedApps

    init {
        loadPinnedApps()
        loadAndCategorizeApps()
    }

    private fun loadAndCategorizeApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _fetchType.value = "CACHE"
            val context = getApplication<Application>().applicationContext
            val packageManager = context.packageManager

            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val allApps = packageManager.queryIntentActivities(intent, 0).map {
                AppInfo(
                    label = it.loadLabel(packageManager),
                    packageName = it.activityInfo.packageName,
                    iconBitmap = it.loadIcon(packageManager).toBitmap().asImageBitmap()
                )
            }.sortedBy { it.label.toString() }

            val prefs = context.getSharedPreferences("AppCategories", Context.MODE_PRIVATE)
            var cachedJson = prefs.getString("categories", null)

            if (cachedJson == null) {
                _fetchType.value = "API"
                cachedJson = fetchAndCategorizeFromApi(allApps)
                prefs.edit { putString("categories", cachedJson) }
            }

            val groupedApps = groupAppsFromJson(cachedJson, allApps)
            _categorizedApps.value = groupedApps
            _isLoading.value = false
        }
    }

    private fun groupAppsFromJson(
        jsonString: String,
        allApps: List<AppInfo>
    ): Map<String, List<AppInfo>> {
        val appToCategoryMap = mutableMapOf<String, String>()
        try {
            val jsonObject = JSONObject(jsonString)
            jsonObject.keys().forEach { packageName ->
                appToCategoryMap[packageName] = jsonObject.getString(packageName)
            }
        } catch (e: JSONException) {
            Log.e("JSON_PARSING", "Failed to parse categories JSON", e)
        }

        return allApps
            .groupBy { app -> appToCategoryMap[app.packageName.toString()] ?: "Other" }
            .toSortedMap()
    }

    private suspend fun fetchAndCategorizeFromApi(apps: List<AppInfo>): String {
        val appListString = apps.joinToString(",\n") { "  \"${it.packageName}\": \"${it.label}\"" }
        val prompt = """
            You are an expert app categorization engine. Your task is to categorize the provided list of Android apps.
            Please categorize them into categories:
            - Social & Communication
            - Entertainment & Media
            - Productivity & Tools
            - Games
            - Finance & Business
            - Lifestyle & Shopping
            - System & Utilities
            - Other
            
            you can even create new categories if needed, but do not use the "Other" category unless absolutely necessary. mare sure too much apps don't end up being in same category.

            Your response MUST be a single, raw JSON object. Do not include any explanatory text, markdown, or anything else outside of the JSON object.
            The JSON object must have package names as keys and their corresponding category as string values.

            Here is the list of apps:
            {
            $appListString
            }
        """.trimIndent()

        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-2.0-flash-001", generationConfig {
                temperature = 0.2f
            })

        return try {
            val response = model.generateContent(prompt)
            Log.d("GEMINI_API", "API response: ${response.text}")
            response.text?.substringAfter('{')?.substringBeforeLast('}')?.let { "{${it}}" } ?: "{}"
        } catch (e: Exception) {
            Log.e("GEMINI_API", "API call failed: ${e.message}", e.cause)
            "{}"
        }
    }

    private fun loadPinnedApps() {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("AppCategories", Context.MODE_PRIVATE)
        _pinnedApps.value = prefs.getStringSet(_pinnedKey, emptySet()) ?: emptySet()
    }

    private fun savePinnedApps(pinned: Set<String>) {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("AppCategories", Context.MODE_PRIVATE)
        prefs.edit { putStringSet(_pinnedKey, pinned) }
        _pinnedApps.value = pinned
    }

    fun togglePinApp(packageName: String) {
        val current = _pinnedApps.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        savePinnedApps(current)
    }

    fun getDisplayCategories(
        allApps: List<AppInfo>,
        categorized: Map<String, List<AppInfo>>,
        searchQuery: String,
        searchBarVisible: Boolean = false
    ): Map<String, List<AppInfo>> {
        val pinned = _pinnedApps.value
        val pinnedApps = allApps.filter { pinned.contains(it.packageName) }
        val result = linkedMapOf<String, List<AppInfo>>()
        val isSearching = searchBarVisible && searchQuery.isNotBlank()

        val filteredPinned = if (isSearching) {
            pinnedApps.filter { app ->
                "Pinned".contains(searchQuery, true) || app.label.contains(searchQuery, true)
            }
        } else pinnedApps
        if (filteredPinned.isNotEmpty()) result["Pinned"] = filteredPinned

        categorized.forEach { (cat, apps) ->
            val nonPinnedApps = apps.filter { !pinned.contains(it.packageName) }
            val filtered = if (isSearching) {
                val categoryMatches = cat.contains(searchQuery, ignoreCase = true)
                val matchingApps =
                    nonPinnedApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
                when {
                    categoryMatches -> nonPinnedApps
                    matchingApps.isNotEmpty() -> matchingApps
                    else -> emptyList()
                }
            } else apps
            if (filtered.isNotEmpty()) result[cat] = filtered
        }
        return result
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: LauncherViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        viewModel = ViewModelProvider(this)[LauncherViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    LauncherRoot(viewModel)
                }
            }
        }
    }

    @Deprecated("")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() = Unit
}

@Composable
fun LauncherRoot(viewModel: LauncherViewModel) {
    val categorizedApps by viewModel.categorizedApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val fetchType by viewModel.fetchType.collectAsState()
    val context = LocalContext.current
    val pinnedApps by viewModel.pinnedApps.collectAsState()

    var searchBarVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    val focusRequester = remember { FocusRequester() }
    val displayCategories by remember(
        categorizedApps, pinnedApps, searchQuery, searchBarVisible
    ) {
        mutableStateOf(
            viewModel.getDisplayCategories(
                allApps = categorizedApps.values.flatten(),
                categorized = categorizedApps,
                searchQuery = searchQuery,
                searchBarVisible = searchBarVisible
            )
        )
    }

    LaunchedEffect(searchQuery) {
        expandedCategories = if (searchQuery.isNotBlank()) displayCategories.keys else emptySet()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(searchBarVisible) {
                detectHorizontalDragGestures(
                    onDragEnd = {},
                    onHorizontalDrag = { _, dragAmount ->
                        if (!searchBarVisible && dragAmount < -30 && !isLoading) {
                            searchBarVisible = true
                        } else if (searchBarVisible && dragAmount > 30 && !isLoading) {
                            searchQuery = ""
                            searchBarVisible = false
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            AnimatedVisibility(visible = searchBarVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .padding(horizontal = 12.dp)
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    modifier = Modifier
                                        .clickable { searchQuery = "" }
                                        .padding(8.dp),
                                    tint = Color.Black.copy(alpha = 0.6f)
                                )
                            }
                        },
                        placeholder = { Text("Search apps or categories") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .focusRequester(focusRequester),
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.9f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.9f),
                        ),
                    )
                }
                if (this.transition.currentState == this.transition.targetState) {
                    focusRequester.requestFocus()
                }
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (fetchType == "API") Text(
                        "Categorizing Apps...",
                        color = Color.White,
                        fontSize = 20.sp
                    ) else Text("Loading Apps...", color = Color.White, fontSize = 20.sp)
                }
            } else {
                if (displayCategories.isEmpty() && searchBarVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No apps found", color = Color.White, fontSize = 18.sp)
                    }
                } else {
                    CategorizedAppList(
                        categorizedApps = displayCategories,
                        expandedCategories = expandedCategories,
                        onAppClick = { app ->
                            val launchIntent =
                                context.packageManager.getLaunchIntentForPackage(app.packageName.toString())
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            }
                        },
                        onAppLongClick = { app ->
                            val wasPinned = pinnedApps.contains(app.packageName)
                            viewModel.togglePinApp(app.packageName.toString())
                            Toast.makeText(
                                context,
                                if (!wasPinned) "App pinned" else "App unpinned",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onCategoryClick = { category, forcedVal ->
                            expandedCategories = if (forcedVal != null) {
                                if (forcedVal) {
                                    expandedCategories + category
                                } else {
                                    expandedCategories - category
                                }
                            } else {
                                if (expandedCategories.contains(category))
                                    expandedCategories - category else expandedCategories + category
                            }
                        },
                        pinnedApps = pinnedApps
                    )
                }
            }
        }
    }
}

@Composable
fun CategorizedAppList(
    categorizedApps: Map<String, List<AppInfo>>,
    expandedCategories: Set<String> = emptySet(),
    onAppClick: (AppInfo) -> Unit,
    onCategoryClick: (String, Boolean?) -> Unit,
    onAppLongClick: (AppInfo) -> Unit = { _ -> },
    pinnedApps: Set<String> = emptySet()
) {
    val listState = rememberLazyListState()
    val prevPinnedCount = remember { mutableIntStateOf(0) }
    val currentPinnedCount = categorizedApps["Pinned"]?.size ?: 0
    val pinnedIndex = categorizedApps.keys.indexOf("Pinned")

    LaunchedEffect(currentPinnedCount) {
        if (prevPinnedCount.intValue == 0 && currentPinnedCount > 0 && pinnedIndex >= 0) {
            listState.animateScrollToItem(pinnedIndex)
            onCategoryClick("Pinned", true)
        }
        prevPinnedCount.intValue = currentPinnedCount
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            categorizedApps.entries.toList(),
            key = { it.key }
        ) { (category, apps) ->
            if (apps.isNotEmpty()) {
                FolderItem(
                    category = category,
                    apps = apps,
                    isExpanded = expandedCategories.contains(category),
                    onCategoryClick = onCategoryClick,
                    onAppClick = onAppClick,
                    onAppLongClick = onAppLongClick,
                    isPinnedCategory = category == "Pinned",
                    pinnedApps = pinnedApps
                )
            }
        }
    }
}

@Composable
fun FolderItem(
    category: String,
    apps: List<AppInfo>,
    isExpanded: Boolean = false,
    onCategoryClick: (String, Boolean?) -> Unit = { _, _ -> },
    onAppClick: (AppInfo) -> Unit,
    isPinnedCategory: Boolean = false,
    onAppLongClick: (AppInfo) -> Unit,
    pinnedApps: Set<String> = emptySet()
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCategoryClick(category, null) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPinnedCategory) {
                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 0f else 35f,
                    label = "PinnedIconRotation"
                )
                Icon(
                    painter = painterResource(R.drawable.push_pin),
                    contentDescription = "Expand or Collapse Pinned category",
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 4.dp)
                        .rotate(rotation)
                )
            } else {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Expand or Collapse category",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = category,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .animateContentSize()
            ) {
                if (isPinnedCategory) {
                    apps.forEach { app ->
                        key(app.packageName) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                AppListItem(
                                    app = app,
                                    onClick = { onAppClick(app) },
                                    onAppLongClick = onAppLongClick,
                                )
                            }
                        }
                    }
                } else {
                    apps.forEach { app ->
                        AppListItem(
                            app = app,
                            onClick = { onAppClick(app) },
                            onAppLongClick = onAppLongClick,
                            isPinned = app.packageName in pinnedApps
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItem(
    app: AppInfo,
    onClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    isPinned: Boolean = false
) {
    Row(
        modifier = Modifier
            .combinedClickable(
                onClick = { onClick(app) },
                onLongClick = { onAppLongClick(app) }
            )
            .padding(vertical = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.iconBitmap,
            contentDescription = app.label.toString(),
            modifier = Modifier.size(32.dp)
        )

        if (isPinned) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .rotate(45f)
                    .background(Color.White)
            )
            Spacer(Modifier.width(6.dp))
        } else Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = app.label.toString(),
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            fontSize = 16.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.5f),
                    offset = Offset(1f, 1f),
                    blurRadius = 2f
                )
            )
        )
    }
}