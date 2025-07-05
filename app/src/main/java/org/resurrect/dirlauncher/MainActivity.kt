package org.resurrect.dirlauncher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

    init {
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
                prefs.edit() { putString("categories", cachedJson) }
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

    override fun onBackPressed() = Unit
}

@Composable
fun LauncherRoot(viewModel: LauncherViewModel) {
    val categorizedApps by viewModel.categorizedApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val fetchType by viewModel.fetchType.collectAsState()
    val context = LocalContext.current

    var searchBarVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    val focusRequester = remember { FocusRequester() }

    val filteredApps = if (searchQuery.isBlank()) categorizedApps else buildMap {
        categorizedApps.forEach { (category, apps) ->
            val categoryMatches = category.contains(searchQuery, ignoreCase = true)
            val matchingApps = apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
            if (categoryMatches || matchingApps.isNotEmpty()) {
                put(category, if (categoryMatches) apps else matchingApps)
            }
        }
    }

    LaunchedEffect(searchQuery) {
        expandedCategories = if (searchQuery.isNotBlank()) filteredApps.keys else emptySet()
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
                if (filteredApps.isEmpty() && searchBarVisible) {
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
                        categorizedApps = filteredApps,
                        expandedCategories = expandedCategories,
                        onAppClick = { app ->
                            val launchIntent =
                                context.packageManager.getLaunchIntentForPackage(app.packageName.toString())
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            }
                        },
                        onCategoryClick = { category ->
                            expandedCategories = if (expandedCategories.contains(category))
                                expandedCategories - category else expandedCategories + category
                        }
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
    onCategoryClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.systemBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(categorizedApps.entries.toList()) { (category, apps) ->
            if (apps.isNotEmpty()) {
                FolderItem(
                    category = category,
                    apps = apps,
                    isExpanded = expandedCategories.contains(category),
                    onCategoryClick = onCategoryClick,
                    onAppClick = onAppClick
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
    onCategoryClick: (String) -> Unit = {},
    onAppClick: (AppInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCategoryClick(category) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Expand or collapse folder",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
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
            Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                apps.forEach { app ->
                    AppIcon(app = app, onAppClick = onAppClick)
                }
            }
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, onAppClick: (AppInfo) -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onAppClick(app) }
            .padding(vertical = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.iconBitmap,
            contentDescription = app.label.toString(),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
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