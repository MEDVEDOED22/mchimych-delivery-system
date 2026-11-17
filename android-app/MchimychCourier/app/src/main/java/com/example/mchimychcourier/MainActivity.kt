package com.example.mchimychcourier

// ОСТАВЬ СВОЮ СТРОЧКУ package com.example.... В САМОМ ВЕРХУ!

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// Твой рабочий URL Google Apps Script:
const val API_URL = "https://script.google.com/macros/s/AKfycbwljhrH_nez7R5RDqPdw3KhcFJd1yku4GrchIxULf3SJ5a7pDdmCHBw9e8bmRd_1b0m/exec"

// Цвета интерфейса Мчимыч
val BrandBlue = Color(0xFF005BFF)
val BgGray = Color(0xFFF4F6F9)
val TimerGreen = Color(0xFF28C76F)
val DeadlineOrange = Color(0xFFFF8A00)
val TanukiRed = Color(0xFFE31E24)
val TextDark = Color(0xFF1A1D20)
val TextMuted = Color(0xFF8E959E)

// Модели данных
data class Courier(
    val id: String,
    val login: String,
    val name: String,
    val phone: String,
    val onShift: Boolean
)

data class OrderItem(
    val id: String,
    val publicNumber: String,
    val address: String,
    val guestPhone: String,
    val items: String,
    val comment: String,
    val paymentStatus: String,
    val deadlineIso: String,
    val status: String, // ASSIGNED, AT_PLACE, GUEST_NO_ANSWER, COMPLETED
    val pendingSync: Boolean = false
)

data class SyncEvent(
    val orderId: String,
    val newStatus: String,
    val clientTimestamp: String,
    val wasOffline: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MchimychApp()
            }
        }
    }
}

// ==========================================
// ЛОКАЛЬНОЕ ХРАНИЛИЩЕ И OFFLINE-ОЧЕРЕДЬ
// ==========================================
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("mchimych_store", Context.MODE_PRIVATE)

    fun saveCourier(c: Courier?) {
        if (c == null) {
            prefs.edit().remove("courier").apply()
            return
        }
        val obj = JSONObject().apply {
            put("id", c.id)
            put("login", c.login)
            put("name", c.name)
            put("phone", c.phone)
            put("onShift", c.onShift)
        }
        prefs.edit().putString("courier", obj.toString()).apply()
    }

    fun getCourier(): Courier? {
        val str = prefs.getString("courier", null) ?: return null
        return try {
            val o = JSONObject(str)
            Courier(
                id = o.optString("id"),
                login = o.optString("login"),
                name = o.optString("name"),
                phone = o.optString("phone"),
                onShift = o.optBoolean("onShift", true)
            )
        } catch (e: Exception) { null }
    }

    fun saveOrders(list: List<OrderItem>) {
        val arr = JSONArray()
        list.forEach { o ->
            arr.put(JSONObject().apply {
                put("id", o.id)
                put("publicNumber", o.publicNumber)
                put("address", o.address)
                put("guestPhone", o.guestPhone)
                put("items", o.items)
                put("comment", o.comment)
                put("paymentStatus", o.paymentStatus)
                put("deadlineIso", o.deadlineIso)
                put("status", o.status)
                put("pendingSync", o.pendingSync)
            })
        }
        prefs.edit().putString("cached_orders", arr.toString()).apply()
    }

    fun getOrders(): List<OrderItem> {
        val str = prefs.getString("cached_orders", null) ?: return emptyList()
        val list = mutableListOf<OrderItem>()
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    OrderItem(
                        id = o.optString("id"),
                        publicNumber = o.optString("publicNumber"),
                        address = o.optString("address"),
                        guestPhone = o.optString("guestPhone"),
                        items = o.optString("items"),
                        comment = o.optString("comment"),
                        paymentStatus = o.optString("paymentStatus", "Оплачен"),
                        deadlineIso = o.optString("deadlineIso"),
                        status = o.optString("status"),
                        pendingSync = o.optBoolean("pendingSync", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun enqueueSyncEvent(event: SyncEvent) {
        val queue = getSyncQueue().toMutableList()
        queue.add(event)
        saveSyncQueue(queue)
    }

    fun getSyncQueue(): List<SyncEvent> {
        val str = prefs.getString("sync_queue", null) ?: return emptyList()
        val list = mutableListOf<SyncEvent>()
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SyncEvent(
                        orderId = o.optString("order_id"),
                        newStatus = o.optString("new_status"),
                        clientTimestamp = o.optString("client_timestamp"),
                        wasOffline = o.optBoolean("was_offline", false)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun clearSyncQueue() {
        prefs.edit().remove("sync_queue").apply()
    }

    private fun saveSyncQueue(queue: List<SyncEvent>) {
        val arr = JSONArray()
        queue.forEach { ev ->
            arr.put(JSONObject().apply {
                put("order_id", ev.orderId)
                put("new_status", ev.newStatus)
                put("client_timestamp", ev.clientTimestamp)
                put("was_offline", ev.wasOffline)
            })
        }
        prefs.edit().putString("sync_queue", arr.toString()).apply()
    }
}

// ==========================================
// СЕТЕВОЙ КЛИЕНТ (С ОБРАБОТКОЙ РЕДИРЕКТОВ GOOGLE)
// ==========================================
object ApiClient {
    suspend fun getCourierOrders(courierId: String): List<OrderItem>? = withContext(Dispatchers.IO) {
        try {
            val url = "$API_URL?action=get_courier_orders&courier_id=$courierId&t=${System.currentTimeMillis()}"
            val response = httpGet(url)
            val json = JSONObject(response)
            if (!json.optBoolean("ok")) return@withContext null

            val arr = json.optJSONArray("orders") ?: JSONArray()
            val result = mutableListOf<OrderItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    OrderItem(
                        id = o.optString("id"),
                        publicNumber = o.optString("public_number"),
                        address = o.optString("address"),
                        guestPhone = o.optString("guest_phone"),
                        items = o.optString("items"),
                        comment = o.optString("comment"),
                        paymentStatus = o.optString("payment_status", "Оплачен"),
                        deadlineIso = o.optString("deadline_iso"),
                        status = o.optString("status"),
                        pendingSync = false
                    )
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    suspend fun login(login: String, pass: String): Pair<Courier?, String?> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("action", "login")
                put("login", login)
                put("password", pass)
            }
            val resStr = httpPost(API_URL, payload.toString())
            val json = JSONObject(resStr)
            if (json.optBoolean("ok")) {
                val c = json.getJSONObject("courier")
                Pair(
                    Courier(
                        id = c.optString("id"),
                        login = c.optString("login"),
                        name = c.optString("name"),
                        phone = c.optString("phone"),
                        onShift = c.optBoolean("on_shift", true)
                    ),
                    null
                )
            } else {
                Pair(null, json.optString("error", "Неверный логин или пароль"))
            }
        } catch (e: Exception) {
            Pair(null, "Ошибка сети. Проверьте подключение.")
        }
    }

    suspend fun flushEvents(events: List<SyncEvent>): Boolean = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext true
        try {
            val arr = JSONArray()
            events.forEach { ev ->
                arr.put(JSONObject().apply {
                    put("order_id", ev.orderId)
                    put("new_status", ev.newStatus)
                    put("client_timestamp", ev.clientTimestamp)
                    put("was_offline", ev.wasOffline)
                })
            }
            val payload = JSONObject().apply {
                put("action", "sync_events")
                put("events", arr)
            }
            val resStr = httpPost(API_URL, payload.toString())
            val json = JSONObject(resStr)
            json.optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun httpGet(urlStr: String): String {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"

        if (conn.responseCode in 301..308) {
            val newUrl = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(newUrl).openConnection() as HttpURLConnection
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        if (code in 301..308) {
            val redirectUrl = conn.getHeaderField("Location")
            conn.disconnect()
            return httpGet(redirectUrl)
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }
}

// ==========================================
// ГЛАВНЫЙ ЭКРАН И УПРАВЛЕНИЕ СОСТОЯНИЕМ
// ==========================================
@Composable
fun MchimychApp() {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    val scope = rememberCoroutineScope()

    var currentCourier by remember { mutableStateOf(store.getCourier()) }
    var orders by remember { mutableStateOf(store.getOrders()) }
    var pendingCount by remember { mutableStateOf(store.getSyncQueue().size) }
    var isOnline by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 - Заказы, 1 - История, 2 - Профиль
    var selectedOrderForDetails by remember { mutableStateOf<OrderItem?>(null) }

    // Посекундный тикер для живых таймеров
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Функция полной синхронизации (сначала выгружает офлайн-очередь, затем обновляет список)
    fun triggerSync(silent: Boolean = false) {
        val courier = currentCourier ?: return
        if (isSyncing) return
        scope.launch {
            isSyncing = true
            val queue = store.getSyncQueue()
            if (queue.isNotEmpty()) {
                val flushed = ApiClient.flushEvents(queue)
                if (flushed) {
                    store.clearSyncQueue()
                    pendingCount = 0
                }
            }

            val remoteOrders = ApiClient.getCourierOrders(courier.id)
            if (remoteOrders != null) {
                // Если остались локальные несинхронизированные события, накладываем их поверх
                val remainingQueue = store.getSyncQueue()
                val merged = remoteOrders.map { remote ->
                    val localEvent = remainingQueue.lastOrNull { it.orderId == remote.id }
                    if (localEvent != null) {
                        remote.copy(status = localEvent.newStatus, pendingSync = true)
                    } else remote
                }
                orders = merged
                store.saveOrders(merged)
                isOnline = true
                if (!silent) {
                    Toast.makeText(context, "Данные синхронизированы", Toast.LENGTH_SHORT).show()
                }
            } else {
                isOnline = false
            }
            isSyncing = false
        }
    }

    // Слушатель появления сети (Автосинхронизация при выходе из подъезда)
    DisposableEffect(currentCourier) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
                triggerSync(silent = true)
            }

            override fun onLost(network: Network) {
                isOnline = false
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, callback)

        onDispose {
            cm.unregisterNetworkCallback(callback)
        }
    }

    // Фоновый опрос каждые 12 секунд
    LaunchedEffect(currentCourier) {
        if (currentCourier != null) {
            triggerSync(silent = true)
            while (true) {
                delay(12000)
                triggerSync(silent = true)
            }
        }
    }

    // Обработка смены статуса заказа (Оптимистичный UI + Offline-First)
    fun updateOrderStatus(order: OrderItem, newStatus: String) {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val exactClientTimestamp = isoFormat.format(Date())

        val event = SyncEvent(
            orderId = order.id,
            newStatus = newStatus,
            clientTimestamp = exactClientTimestamp,
            wasOffline = !isOnline
        )

        // 1. Мгновенно обновляем UI и локальный кэш (без ожидания сети!)
        val updatedList = orders.map {
            if (it.id == order.id) it.copy(status = newStatus, pendingSync = true) else it
        }
        orders = updatedList
        store.saveOrders(updatedList)

        if (selectedOrderForDetails?.id == order.id) {
            selectedOrderForDetails = if (newStatus == "COMPLETED") null
            else selectedOrderForDetails?.copy(status = newStatus, pendingSync = true)
        }

        // 2. Кладем событие в надежную очередь
        store.enqueueSyncEvent(event)
        pendingCount = store.getSyncQueue().size

        // 3. Пытаемся отправить сразу, если сеть есть
        triggerSync(silent = true)
    }

    if (currentCourier == null) {
        LoginScreen(
            onLoginSuccess = { courier ->
                store.saveCourier(courier)
                currentCourier = courier
            }
        )
    } else {
        Scaffold(
            containerColor = BgGray,
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.ShoppingBag, contentDescription = null) },
                        label = { Text("Заказы") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = BrandBlue, selectedTextColor = BrandBlue)
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                        label = { Text("История") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = BrandBlue, selectedTextColor = BrandBlue)
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("Профиль") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = BrandBlue, selectedTextColor = BrandBlue)
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Шапка и умный индикатор сети / очереди синхронизации
                TopHeaderBar(
                    title = when (selectedTab) {
                        0 -> "Заказы"
                        1 -> "История"
                        else -> "Профиль"
                    },
                    isOnline = isOnline,
                    isSyncing = isSyncing,
                    pendingCount = pendingCount,
                    onManualRefresh = { triggerSync(silent = false) }
                )

                when (selectedTab) {
                    0 -> {
                        val active = orders.filter { it.status != "COMPLETED" }
                        if (active.isEmpty()) {
                            EmptyBox("Нет активных заказов")
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(active, key = { it.id }) { order ->
                                    OrderCard(
                                        order = order,
                                        nowMillis = nowMillis,
                                        onClick = { selectedOrderForDetails = order }
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        val completed = orders.filter { it.status == "COMPLETED" }
                        if (completed.isEmpty()) {
                            EmptyBox("История за смену пуста")
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(completed, key = { it.id }) { order ->
                                    HistoryOrderCard(order)
                                }
                            }
                        }
                    }

                    2 -> {
                        ProfileScreen(
                            courier = currentCourier!!,
                            pendingCount = pendingCount,
                            onForceSync = { triggerSync(silent = false) },
                            onLogout = {
                                store.saveCourier(null)
                                store.clearSyncQueue()
                                currentCourier = null
                            }
                        )
                    }
                }
            }

            // Модальное окно деталей заказа и кнопок управления
            if (selectedOrderForDetails != null) {
                OrderDetailsSheet(
                    order = selectedOrderForDetails!!,
                    onDismiss = { selectedOrderForDetails = null },
                    onStatusChange = { newStatus ->
                        updateOrderStatus(selectedOrderForDetails!!, newStatus)
                    }
                )
            }
        }
    }
}

// ==========================================
// 1. ЭКРАН АВТОРИЗАЦИИ (1-в-1 МЧИМЫЧ)
// ==========================================
@Composable
fun LoginScreen(onLoginSuccess: (Courier) -> Unit) {
    var login by remember { mutableStateOf("ilya") }
    var password by remember { mutableStateOf("1234") }
    var showPassword by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGray)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "МЧИМЫЧ",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            color = BrandBlue,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(36.dp))

        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            placeholder = { Text("Логин", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFE9ECEF),
                unfocusedContainerColor = Color(0xFFE9ECEF),
                focusedBorderColor = BrandBlue,
                unfocusedBorderColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("Пароль", color = TextMuted) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = TextMuted
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFE9ECEF),
                unfocusedContainerColor = Color(0xFFE9ECEF),
                focusedBorderColor = BrandBlue,
                unfocusedBorderColor = Color.Transparent
            )
        )

        if (errorMsg != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = errorMsg!!, color = TanukiRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = agreed,
                onCheckedChange = { agreed = it },
                colors = CheckboxDefaults.colors(checkedColor = BrandBlue)
            )
            Text(
                text = buildAnnotatedString {
                    append("Согласен с ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append("Политикой конфиденциальности")
                    }
                    append(" и ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append("Пользовательским соглашением")
                    }
                },
                fontSize = 12.sp,
                color = TextDark
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (!agreed || login.isBlank() || password.isBlank()) return@Button
                isLoading = true
                errorMsg = null
                scope.launch {
                    val (courier, err) = ApiClient.login(login, password)
                    isLoading = false
                    if (courier != null) {
                        onLoginSuccess(courier)
                    } else {
                        errorMsg = err
                    }
                }
            },
            enabled = agreed && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("Войти", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.weight(1.2f))

        Text(
            text = "Версия приложения: 3.0.22",
            fontSize = 12.sp,
            color = TextMuted
        )
    }
}

// ==========================================
// 2. ШАПКА И КАРТОЧКА ЗАКАЗА
// ==========================================
@Composable
fun TopHeaderBar(
    title: String,
    isOnline: Boolean,
    isSyncing: Boolean,
    pendingCount: Int,
    onManualRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgGray)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextDark
            )

            IconButton(
                onClick = onManualRefresh,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        color = BrandBlue,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = BrandBlue)
                }
            }
        }

        // Неблокирующий баннер состояния сети / очереди
        if (!isOnline || pendingCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            val bannerBg = if (!isOnline) Color(0xFFFEF3C7) else Color(0xFFE0F2FE)
            val bannerColor = if (!isOnline) Color(0xFFD97706) else Color(0xFF0284C7)
            val bannerText = when {
                !isOnline && pendingCount > 0 -> "Нет сети • В очереди на отправку: $pendingCount"
                !isOnline -> "Офлайн-режим • Заказы доступны локально"
                else -> "Синхронизация очереди ($pendingCount)..."
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bannerBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (!isOnline) Icons.Default.CloudOff else Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = bannerColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = bannerText, color = bannerColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun OrderCard(
    order: OrderItem,
    nowMillis: Long,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val parts = order.publicNumber.split("/")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Верхний ряд: Номер чека и статус оплаты
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = TextDark)) {
                            append(parts.getOrNull(0)?.trim() ?: order.publicNumber)
                        }
                        if (parts.size > 1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextMuted)) {
                                append(" / ${parts[1].trim()}")
                            }
                        }
                    },
                    fontSize = 22.sp
                )

                Surface(
                    color = Color(0xFFF4F6F9),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ ${order.paymentStatus}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ряд с зеленым таймером и статусом (На месте / Не отвечает)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(TanukiRed),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⛩", color = Color.White, fontSize = 13.sp)
                }

                Surface(
                    color = TimerGreen,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "🕒 Доставить за ${formatCountdown(order.deadlineIso, nowMillis)}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                if (order.status == "AT_PLACE") {
                    Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = "📍 На месте",
                            color = Color(0xFFD97706),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (order.status == "GUEST_NO_ANSWER") {
                    Surface(color = Color(0xFFFEE2E2), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = "⚠️ Не отвечает",
                            color = TanukiRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Заголовок "Адрес гостя" и оранжевый дедлайн
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("Адрес гостя", fontSize = 13.sp, color = TextMuted)

                Surface(
                    color = DeadlineOrange,
                    shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                ) {
                    Text(
                        text = "🔥 ${formatDeadlineClock(order.deadlineIso)}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Серая плашка с адресом и кнопкой копирования
            Surface(
                color = Color(0xFFF4F6F9),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = order.address,
                        fontSize = 14.sp,
                        color = TextDark,
                        modifier = Modifier.weight(1f),
                        lineHeight = 19.sp
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("address", order.address))
                            Toast.makeText(context, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Копировать",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Кнопка перехода в Яндекс Карты с автоподстановкой адреса
            Button(
                onClick = { openYandexMaps(context, order.address) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF4F6F9),
                    contentColor = TextDark
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Text("➤ ", color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("Яндекс Карты", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ==========================================
// 3. МОДАЛКА ДЕТАЛЕЙ ЗАКАЗА И СМЕНЫ КНОПОК
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailsSheet(
    order: OrderItem,
    onDismiss: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    val context = LocalContext.current
    var noAnswerSecondsLeft by remember { mutableStateOf(0) }

    // 5-минутный таймер ожидания, если гость не отвечает
    LaunchedEffect(noAnswerSecondsLeft) {
        if (noAnswerSecondsLeft > 0) {
            delay(1000)
            noAnswerSecondsLeft -= 1
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Заказ ${order.publicNumber}",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextDark
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("Адрес:", fontSize = 13.sp, color = TextMuted)
            Text(order.address, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextDark)

            Spacer(modifier = Modifier.height(12.dp))

            Text("Состав заказа:", fontSize = 13.sp, color = TextMuted)
            Surface(
                color = BgGray,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = order.items.ifBlank { "Стандартный набор доставки" },
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp,
                    color = TextDark
                )
            }

            if (order.comment.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Комментарий гостя:", fontSize = 13.sp, color = TextMuted)
                Text(order.comment, fontSize = 14.sp, color = TextDark)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Кнопка звонка гостю
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.guestPhone}"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Позвонить гостю (${order.guestPhone})")
            }

            if (noAnswerSecondsLeft > 0 || order.status == "GUEST_NO_ANSWER") {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val min = noAnswerSecondsLeft / 60
                    val sec = noAnswerSecondsLeft % 60
                    Text(
                        text = if (noAnswerSecondsLeft > 0)
                            "Диспетчер уведомлен • Ожидание гостя: %02d:%02d".format(min, sec)
                        else "Статус «Гость не отвечает» передан диспетчеру",
                        color = TanukiRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Две главные кнопки внизу с плавной анимацией перехода "На месте" -> "Завершить заказ"
            val isAtPlace = order.status == "AT_PLACE" || order.status == "GUEST_NO_ANSWER"
            val mainBtnColor by animateColorAsState(
                targetValue = if (isAtPlace) TimerGreen else BrandBlue,
                animationSpec = tween(durationMillis = 350),
                label = "btnColor"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        noAnswerSecondsLeft = 300
                        onStatusChange("GUEST_NO_ANSWER")
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TanukiRed)
                ) {
                    Text("Гость не отвечает", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (!isAtPlace) {
                            onStatusChange("AT_PLACE")
                        } else {
                            onStatusChange("COMPLETED")
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = mainBtnColor)
                ) {
                    AnimatedContent(targetState = isAtPlace, label = "btnText") { arrived ->
                        if (!arrived) {
                            Text("На месте", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Завершить заказ", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. ИСТОРИЯ И ПРОФИЛЬ
// ==========================================
@Composable
fun HistoryOrderCard(order: OrderItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(order.publicNumber, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextDark)
                Spacer(modifier = Modifier.height(4.dp))
                Text(order.address, fontSize = 13.sp, color = TextMuted, maxLines = 2)
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (order.pendingSync) {
                Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "⏳ Ждет сети",
                        color = Color(0xFFD97706),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                Surface(color = Color(0xFFDCFCE7), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "✓ Закрыт",
                        color = Color(0xFF15803D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    courier: Courier,
    pendingCount: Int,
    onForceSync: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(courier.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Логин: ${courier.login} • ${courier.phone}", color = TextMuted, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Surface(color = Color(0xFFDCFCE7), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = "● Смена активна",
                        color = Color(0xFF15803D),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Button(
            onClick = onForceSync,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
        ) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Принудительная синхронизация (Очередь: $pendingCount)")
        }

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TanukiRed)
        ) {
            Text("Завершить смену и выйти", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextMuted, fontSize = 16.sp)
    }
}

// ==========================================
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ (КАРТЫ И ВРЕМЯ)
// ==========================================
fun openYandexMaps(context: Context, address: String) {
    val encoded = Uri.encode(address)
    val yandexUri = Uri.parse("yandexmaps://maps.yandex.ru/?text=$encoded")
    val geoUri = Uri.parse("geo:0,0?q=$encoded")
    val webUri = Uri.parse("https://yandex.ru/maps/?text=$encoded")

    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, yandexUri))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}

fun parseIsoMillis(iso: String): Long? {
    if (iso.isBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )
    for (p in patterns) {
        try {
            val sdf = SimpleDateFormat(p, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.parse(iso)?.time
        } catch (_: Exception) {}
    }
    return null
}

fun formatCountdown(deadlineIso: String, nowMillis: Long): String {
    val target = parseIsoMillis(deadlineIso) ?: return "01:00:00"
    val diffSec = ((target - nowMillis) / 1000).coerceAtLeast(0)
    val h = diffSec / 3600
    val m = (diffSec % 3600) / 60
    val s = diffSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

fun formatDeadlineClock(deadlineIso: String): String {
    val target = parseIsoMillis(deadlineIso) ?: return "22:00"
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(target))
}