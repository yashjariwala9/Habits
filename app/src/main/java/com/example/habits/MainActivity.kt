package com.example.habits

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.habits.ui.theme.HabitsTheme
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// Main activity that handles the lifecycle and sets up the app
class MainActivity : ComponentActivity() {
    private lateinit var workManager: WorkManager
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    // Called when the activity is first created
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workManager = WorkManager.getInstance(applicationContext)

        // Set up a launcher for requesting permissions
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                // If permission is denied, open system settings to manually grant permission
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${packageName}")
                startActivity(intent)
            }
        }

        // Check and request storage permission if not granted
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Set the UI content of the activity
        setContent {
            HabitsTheme {
                HabitTrackerApp(::scheduleNotification, ::cancelWork)
            }
        }
    }

    // Schedule a notification for a habit based on its reminder time
    private fun scheduleNotification(habit: Habit) {
        habit.reminder?.let { reminderTime ->
            val currentTime = LocalDateTime.now()
            val initialDelay = java.time.Duration.between(currentTime, reminderTime)

            if (initialDelay.isNegative) {
                // If the reminder time is in the past, schedule it for the next day
                val nextReminderTime = reminderTime.plusDays(habit.frequency?.toLong() ?: 1)
                val delayUntilNextDay = java.time.Duration.between(currentTime, nextReminderTime)
                scheduleWork(habit, delayUntilNextDay.toMillis())
            } else {
                // Schedule the work with the calculated delay
                scheduleWork(habit, initialDelay.toMillis())
            }
        }
    }

    // Create and enqueue a work request to show a notification
    private fun scheduleWork(habit: Habit, initialDelayMillis: Long) {
        val notificationRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(NotificationWorker.KEY_HABIT_NAME to habit.name))
            .build()

        // Enqueue the work request with a unique name for each habit
        workManager.enqueueUniqueWork(
            "notification_${habit.id}",
            ExistingWorkPolicy.REPLACE,
            notificationRequest
        )
    }

    // Cancel the scheduled work for a specific habit
    fun cancelWork(habit: Habit) {
        workManager.cancelUniqueWork("notification_${habit.id}")
    }
}

// Data class to represent a habit with relevant fields
data class Habit(
    val id: Int,
    var name: String,
    var isCompleted: Boolean = false,
    var reminder: LocalDateTime? = null,
    var frequency: Int? = null
)

// Worker class that handles showing notifications in the background
class NotificationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        const val KEY_HABIT_NAME = "KEY_HABIT_NAME"
        private const val CHANNEL_ID = "HabitReminderChannel"
        private const val NOTIFICATION_ID = 1
    }

    // Called when the worker is executed to show the notification
    override fun doWork(): Result {
        val habitName = inputData.getString(KEY_HABIT_NAME) ?: return Result.failure()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the notification channel if it doesn't exist
        createNotificationChannel(notificationManager)

        // Build and display the notification
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Habit Reminder")
            .setContentText("Time to complete your habit: $habitName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI) // Set default notification sound
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        return Result.success()
    }

    // Create a notification channel for habit reminders
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Habit Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for Habit Reminders"
            enableLights(true)
            enableVibration(true)
            lightColor = android.graphics.Color.RED
            vibrationPattern = longArrayOf(1000, 1000) // Vibration pattern
            // Optionally set a custom sound
            // sound = Uri.parse("android.resource://${applicationContext.packageName}/raw/notification_sound")
        }

        notificationManager.createNotificationChannel(channel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitTrackerApp(
    scheduleNotification: (Habit) -> Unit,
    cancelWork: (Habit) -> Unit
) {
    // State to manage the list of habits and other UI states
    var habits by remember { mutableStateOf(listOf<Habit>()) }
    var nextId by remember { mutableStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var currentHabit by remember { mutableStateOf<Habit?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Habit Tracker") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Show dialog to add a new habit
                    currentHabit = Habit(nextId, "New Habit ${nextId + 1}")
                    showDialog = true
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Habit")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Display the list of habits
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 64.dp) // Add padding for the FAB
            ) {
                items(habits) { habit ->
                    HabitItem(
                        habit = habit,
                        onComplete = { completedHabit ->
                            habits = habits.map { if (it.id == completedHabit.id) completedHabit else it }
                        },
                        onEdit = {
                            // Show dialog to edit the selected habit
                            currentHabit = habit
                            showDialog = true
                        },
                        onDelete = {
                            // Remove habit and cancel any scheduled work
                            habits = habits.filter { it.id != habit.id }
                            cancelWork(habit)
                        }
                    )
                }
            }

            // Show dialog for adding or editing a habit
            if (showDialog) {
                HabitDialog(
                    habit = currentHabit!!,
                    onDismiss = { showDialog = false },
                    onConfirm = { updatedHabit ->
                        habits = if (habits.any { it.id == updatedHabit.id }) {
                            // Update existing habit
                            habits.map { if (it.id == updatedHabit.id) updatedHabit else it }
                        } else {
                            // Add new habit
                            habits + updatedHabit
                        }
                        if (updatedHabit.id == nextId) {
                            nextId++
                        }
                        // Schedule a notification for the updated habit
                        scheduleNotification(updatedHabit)
                        showDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun HabitItem(
    habit: Habit,
    onComplete: (Habit) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox to mark habit as completed
        Checkbox(
            checked = habit.isCompleted,
            onCheckedChange = { isChecked ->
                // Update habit completion status
                onComplete(habit.copy(isCompleted = isChecked))
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            // Display habit name and reminder details
            Text(habit.name)
            habit.reminder?.let {
                Text(
                    "Reminder: ${it.format(DateTimeFormatter.ofPattern("hh:mm a"))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit Habit")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete Habit")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDialog(
    habit: Habit,
    onDismiss: () -> Unit,
    onConfirm: (Habit) -> Unit
) {
    // State for managing dialog input fields
    var name by remember { mutableStateOf(habit.name) }
    var date by remember { mutableStateOf(habit.reminder?.toLocalDate() ?: LocalDate.now()) }
    var time by remember { mutableStateOf(habit.reminder?.toLocalTime() ?: LocalTime.now()) }
    var frequency by remember { mutableStateOf(habit.frequency ?: 1) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    val timePickerState = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (habit.id == -1) "Add Habit" else "Edit Habit") },
        text = {
            Column {
                // Input fields for habit details
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Habit Name") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showDatePicker = true }) {
                    Text("Select Date: ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showTimePicker = true }) {
                    Text("Select Time: ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Frequency: $frequency day(s)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = frequency.toFloat(),
                    onValueChange = { frequency = it.toInt() },
                    valueRange = 1f..7f,
                    steps = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Confirm the habit update or addition
                val reminder = LocalDateTime.of(date, time)
                onConfirm(habit.copy(name = name, reminder = reminder, frequency = frequency))
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // DatePickerDialog to select a date
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // TimePickerDialog to select a time
    if (showTimePicker) {
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            TimePicker(state = timePickerState)
        }
    }
}

@Composable
fun PermissionRequestDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission Required") },
        text = {
            Text("This app requires access to your files. Please grant permission in settings.")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Open Settings")
            }
        }
    )
}

// Custom TimePickerDialog Composable
@Composable
fun TimePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        text = { content() }
    )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    HabitsTheme {
        HabitTrackerApp(
            scheduleNotification = {},
            cancelWork = {}
        )
    }
}