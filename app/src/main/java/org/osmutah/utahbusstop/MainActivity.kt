package org.osmutah.utahbusstop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maproulette.sdk.Bounds
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.ChoiceSubmission
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.MapRouletteException
import org.maproulette.sdk.MobileSupport
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskFilter
import org.maproulette.sdk.TaskId
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.mobileSupport
import org.osmutah.utahbusstop.auth.AppSession
import org.osmutah.utahbusstop.auth.SignInLauncher

/** One stop being walked through: its questions, the answers so far, and which question is showing. */
private class AnswerFlow(val stop: NearbyStop, val task: Task, val client: MapRouletteClient, val questions: List<ChoiceQuestion>) {
    val answers = linkedMapOf<String, String>()
    val cantTell = mutableSetOf<String>()
    var index = 0
}

/** A beginner-friendly walk-and-answer app for Utah bus stops. Challenge IDs never come from user input or search. */
class MainActivity : Activity() {
    private enum class Screen { WELCOME, RIDING, NEARBY, QUESTION, CONFIRM, THANKS }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backend = Backend.STAGE
    private lateinit var session: AppSession
    private lateinit var sessionClient: AppSession.SessionClient
    private lateinit var signIn: SignInLauncher
    private var accountObserver: Job? = null
    private var request: Job? = null
    private var submissionInFlight = false

    private var screen = Screen.WELCOME
    private var showMap = false
    private var locationIsApproximate = false
    private var searchCenter = DOWNTOWN
    private var searchStarted = false
    private var loading = false
    private var stops: List<NearbyStop> = emptyList()
    private val stopNames = mutableMapOf<Long, String>()
    private var selectedStopId: Long? = null
    private var listNotice: String? = null
    private var flow: AnswerFlow? = null
    private val helpedStops = mutableSetOf<Long>()
    private var signInNotice: String? = null
    private var firstAccountMessage: String? = null
    private var clientGeneration = -1L
    private var ridingSince = 0L
    private var ridingMessage: String? = null
    private var ridingTimeout: Job? = null

    private val arrows = mutableListOf<DirectionArrow>()
    private var shownHeading: Float? = null
    private var smoothX = 0f
    private var smoothY = 0f
    private var haveHeading = false
    private var declination = 0f
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val compass = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val remapped = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            // Held upright, like a camera, the top of the phone points at the sky, so face along its back instead.
            val matrix = if (abs(rotation[8]) < 0.7f) {
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                remapped
            } else rotation
            SensorManager.getOrientation(matrix, orientation)
            val radians = Math.toRadians(Math.toDegrees(orientation[0].toDouble()) + declination)
            // Smooth the heading as a direction on a circle, so 359° to 1° never swings the long way round.
            if (!haveHeading) { smoothX = cos(radians).toFloat(); smoothY = sin(radians).toFloat(); haveHeading = true }
            else { smoothX += SMOOTHING * (cos(radians).toFloat() - smoothX); smoothY += SMOOTHING * (sin(radians).toFloat() - smoothY) }
            val heading = ((Math.toDegrees(atan2(smoothY, smoothX).toDouble()) + 360) % 360).toFloat()
            val last = shownHeading
            if (last != null && abs(((heading - last + 540) % 360) - 180) < MIN_TURN_DEGREES) return
            shownHeading = heading
            arrows.removeAll { !it.isAttachedToWindow }
            arrows.forEach { it.pointAt(heading) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startCompass() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sensorManager?.registerListener(compass, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopCompass() {
        sensorManager?.unregisterListener(compass)
        haveHeading = false
    }

    private var note: View? = null
    private var noteTimer: Job? = null
    private var listContainer: LinearLayout? = null
    private var mapCardHolder: FrameLayout? = null
    private var mapView: MapView? = null
    private var stopMap: StopMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.PAPER
        if (Build.VERSION.SDK_INT >= 34) {
            // No slide when another screen opens over this one or closes back to it, e.g. the browser sign-in.
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        session = AppSession.get(applicationContext, backend)
        sessionClient = session.newClient()
        clientGeneration = session.view.value.generation
        signIn = SignInLauncher(this, session, scope).apply { restore(savedInstanceState) }
        if (session.view.value.signedIn) showNearby() else showWelcome()
        accountObserver = scope.launch {
            session.view.collect { view ->
                // A data client only works for the sign-in session it was made in, so make a new one after sign-in or sign-out.
                if (view.generation != clientGeneration) {
                    sessionClient.close()
                    sessionClient = session.newClient()
                    clientGeneration = view.generation
                }
                if (firstAccountMessage == null) firstAccountMessage = view.message
                else if (view.message != firstAccountMessage && !view.signedIn) signInNotice = view.message
                when {
                    view.signedIn && screen == Screen.WELCOME -> showNearby()
                    view.signedIn && screen == Screen.RIDING -> {
                        // Let the bus ride at least a moment so the screen doesn't flash past.
                        val wait = MIN_RIDE_MILLIS - (System.currentTimeMillis() - ridingSince)
                        if (wait > 0) delay(wait)
                        if (screen == Screen.RIDING) showNearby()
                    }
                    !view.signedIn && screen == Screen.RIDING -> if (view.message != ridingMessage) showWelcome()
                    !view.signedIn && screen != Screen.WELCOME && !submissionInFlight -> showWelcome()
                }
            }
        }
    }

    // ---- Screens -------------------------------------------------------------------------------

    private fun show(newScreen: Screen, content: View) {
        if (newScreen != Screen.NEARBY) { releaseMap(); stopCompass() } // the nearby screen releases the old map before building its own
        listContainer = null
        mapCardHolder = null
        screen = newScreen
        // The app draws edge to edge, so keep each screen clear of the status and navigation bars.
        val (left, top, right, bottom) = listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
        content.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(left, top + insets.systemWindowInsetTop, right, bottom + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(content)
    }

    private fun showWelcome() {
        request?.cancel()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAPER) }
        column.addView(BusStopArt(this).apply { background = rounded(Palette.GREEN_SOFT, 40) }, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(36))
            addView(label("Help make bus stops better for everyone", 30, bold = true))
            addView(label("Find a stop near you, look at it, and answer one easy question. It takes about a minute.", 17, Palette.MUTED).apply { setPadding(0, dp(12), 0, dp(20)) })
            addView(primaryButton("Sign in to start") {
                when {
                    submissionInFlight -> toast("Please wait a moment.")
                    !session.signInAvailable -> showSignInSetup()
                    else -> signIn.signIn()
                }
            })
            addView(label("Signing in uses a free OpenStreetMap account. New here? You can create one in a minute.", 14, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
            signInNotice?.let { addView(label(it, 13, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }) }
        }, LinearLayout.LayoutParams(-1, -2))
        show(Screen.WELCOME, column)
    }

    /** Shown between the browser sign-in and the stop list, so the welcome screen never flashes back. */
    private fun showRiding() {
        request?.cancel()
        ridingSince = System.currentTimeMillis()
        ridingMessage = session.view.value.message
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Palette.PAPER); setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        column.addView(RidingBus(this), LinearLayout.LayoutParams(dp(300), dp(150)))
        column.addView(label("Riding to your stops…", 26, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) })
        column.addView(label("Getting everything ready", 16, Palette.MUTED).apply { gravity = Gravity.CENTER })
        show(Screen.RIDING, column)
        ridingTimeout?.cancel()
        ridingTimeout = scope.launch {
            delay(RIDE_TIMEOUT_MILLIS)
            if (screen == Screen.RIDING) { signInNotice = "Sign-in did not finish. Please try again."; showWelcome() }
        }
    }

    private fun showNearby() {
        request?.cancel()
        flow = null
        releaseMap()
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.PAPER) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Stops near you", 28, bold = true))
        titles.addView(label(if (locationIsApproximate) "Around downtown Salt Lake City" else "Sorted by distance", 15, Palette.MUTED))
        titleRow.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(textLink("Sign out") { confirmSignOut() })
        header.addView(titleRow)
        header.addView(segmented(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (locationIsApproximate && !loading) {
            header.addView(card(radiusDp = 18, fill = Palette.WARN_BG, stroke = null) {
                addView(label("Allow location to see stops around you.", 15, Palette.WARN_INK, bold = true))
                addView(textLink("Turn on location") { requestLocationAndLoad() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        var list: LinearLayout? = null
        var holder: FrameLayout? = null
        if (showMap) {
            root.addView(buildMapView(), FrameLayout.LayoutParams(-1, -1))
            val fade = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.PAPER, 0x00F5F7F1))
            }
            root.addView(fade, FrameLayout.LayoutParams(-1, dp(230), Gravity.TOP))
            root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            holder = FrameLayout(this)
            root.addView(holder, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(24)) })
        } else {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(header)
            list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(28)) }
            column.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(column, FrameLayout.LayoutParams(-1, -1))
        }
        show(Screen.NEARBY, root)
        listContainer = list
        mapCardHolder = holder
        if (showMap) attachMap()
        startCompass()
        renderStops()
        if (!searchStarted) requestLocationAndLoad()
    }

    private fun segmented(): View = LinearLayout(this).apply {
        background = rounded(0xFFE8EDE5.toInt(), 24)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        fun segment(text: String, selected: Boolean, wantsMap: Boolean) = TextView(this@MainActivity).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Palette.INK else Palette.MUTED)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            if (selected) background = rounded(Palette.WHITE, 20)
            isClickable = true
            setOnClickListener { if (showMap != wantsMap) { showMap = wantsMap; showNearby() } }
        }
        addView(segment("List", !showMap, false), LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(segment("Map", showMap, true), LinearLayout.LayoutParams(0, dp(44), 1f))
    }

    /** Redraws whichever of the list or the map card is on screen, without rebuilding the map. */
    private fun renderStops() {
        listContainer?.let { list ->
            list.removeAllViews()
            if (loading) {
                list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
                    addView(ProgressBar(this@MainActivity))
                    addView(label("  Looking for stops near you…", 16, Palette.MUTED))
                })
                return@let
            }
            listNotice?.let {
                list.addView(label(it, 16, Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(8)) })
                list.addView(secondaryButton("Try again") { requestLocationAndLoad() })
            }
            stops.forEach { list.addView(stopRow(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
        }
        mapCardHolder?.let { holder ->
            holder.removeAllViews()
            holder.addView(mapStopCard())
        }
        stopMap?.update(stops, selectedStopId)
    }

    private fun stopRow(stop: NearbyStop): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.WHITE, 22, Palette.BORDER)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true
        setOnClickListener { openStop(stop) }
        addView(directionArrow(stop), LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(label(stopName(stop), 18, bold = true))
            addView(label(howFar(stop), 15, Palette.MUTED))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("›", 26, Palette.MUTED))
    }

    private fun directionArrow(stop: NearbyStop) = DirectionArrow(this, stop.bearingDegrees.toFloat()).also {
        it.pointAt(shownHeading)
        arrows.add(it)
    }

    private fun stopName(stop: NearbyStop) = stopNames[stop.taskId] ?: "Bus stop"
    private fun howFar(stop: NearbyStop) = "${friendlyDistance(stop.distanceMeters)} ${directionWords(stop.bearingDegrees)}"

    private fun mapStopCard(): View {
        val stop = stops.firstOrNull { it.taskId == selectedStopId } ?: stops.firstOrNull()
        return card(radiusDp = 28) {
            if (stop == null) {
                addView(label(if (loading) "Looking for stops near you…" else listNotice ?: "No stops to show yet.", 16, Palette.MUTED))
                return@card
            }
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(directionArrow(stop), LinearLayout.LayoutParams(dp(52), dp(52)))
            row.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(label(stopName(stop), 18, bold = true))
                addView(label(howFar(stop), 15, Palette.MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(row)
            addView(primaryButton("Help with this stop") { openStop(stop) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    private fun showQuestion() {
        val current = flow ?: return showNearby()
        val question = current.questions[current.index]
        val answered = question.id in current.answers || question.id in current.cantTell
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAPER) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(label("‹", 28, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back"
            background = rounded(Palette.WHITE, 22, Palette.BORDER)
            isClickable = true
            setOnClickListener { goBackOneQuestion(current) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("Question ${current.index + 1} of ${current.questions.size}", 14, Palette.MUTED, bold = true))
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = current.questions.size
                progress = current.index + 1
                progressTintList = ColorStateList.valueOf(Palette.GREEN)
            }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(bar)
        top.addView(label("${stopName(current.stop)} · ${howFar(current.stop)}", 15, Palette.MUTED).apply { setPadding(0, dp(14), 0, 0) })
        column.addView(top)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        body.addView(label(question.prompt, 28, bold = true))
        question.description?.takeIf(String::isNotBlank)?.let { help ->
            body.addView(card(radiusDp = 20, fill = Palette.GREEN_SOFT, stroke = null) { addView(label(help, 16, 0xFF24402F.toInt())) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        fun choice(text: String, optionId: String?) {
            val picked = if (optionId == null) question.id in current.cantTell else current.answers[question.id] == optionId
            body.addView(android.widget.Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (picked) Palette.WHITE else Palette.INK)
                stateListAnimator = null
                background = rounded(if (picked) Palette.GREEN else Palette.WHITE, 24, if (picked) Palette.GREEN else Palette.BORDER)
                minHeight = dp(64)
                minimumHeight = dp(64)
                setOnClickListener {
                    if (optionId == null) { current.answers.remove(question.id); current.cantTell.add(question.id) }
                    else { current.cantTell.remove(question.id); current.answers[question.id] = optionId }
                    showQuestion()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        question.options.forEach { choice(it.label, it.id) }
        choice("I can't tell", null)
        column.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        val last = current.index == current.questions.lastIndex
        bottom.addView(primaryButton(if (last) "Review my answers" else "Next", enabled = answered) {
            if (last) showConfirm() else { current.index++; showQuestion() }
        })
        bottom.addView(textLink("Skip this stop") { showNearby() })
        column.addView(bottom)
        show(Screen.QUESTION, column)
    }

    private fun goBackOneQuestion(current: AnswerFlow) {
        if (current.index > 0) { current.index--; showQuestion() } else showNearby()
    }

    private fun showConfirm() {
        val current = flow ?: return showNearby()
        val answered = current.questions.filter { it.id in current.answers }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAPER) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(12)) }
        if (answered.isEmpty()) {
            body.addView(label("Nothing to save", 30, bold = true))
            body.addView(label("You chose \"I can't tell\" for every question, so there is nothing to add to the map. That is fine. Pick another stop, or go back to change an answer.", 17, Palette.MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            body.addView(label("Ready to save your answers?", 30, bold = true))
            body.addView(label("Please check that this is what you saw at the stop.", 17, Palette.MUTED).apply { setPadding(0, dp(10), 0, dp(14)) })
            body.addView(card {
                addView(label("${stopName(current.stop)} · ${howFar(current.stop)}", 15, Palette.MUTED))
                addView(label("You are adding to the map:", 15, Palette.MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
                answered.forEach { question ->
                    val option = question.options.first { it.id == current.answers[question.id] }
                    addView(card(radiusDp = 18, fill = Palette.GREEN_SOFT, stroke = null) {
                        addView(label(question.prompt, 14, Palette.MUTED))
                        addView(label(option.label, 20, Palette.INK, bold = true))
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
            })
            body.addView(card(fill = Palette.WARN_BG, stroke = null) {
                addView(label("This is public.", 16, Palette.WARN_INK, bold = true))
                addView(label("Your answers go onto OpenStreetMap, a free map used by many apps and websites. Anyone can see them, along with your OpenStreetMap username.", 16, Palette.WARN_INK))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        column.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        if (answered.isNotEmpty()) {
            val canSave = accountCanSubmit(session.view.value)
            bottom.addView(primaryButton(if (canSave) "Yes, save my answers" else "Your account can't save changes yet", enabled = canSave) { submit() })
        } else {
            bottom.addView(primaryButton("Back to nearby stops") { showNearby() })
        }
        bottom.addView(secondaryButton("Go back and change it") { current.index = current.questions.lastIndex; showQuestion() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        column.addView(bottom)
        show(Screen.CONFIRM, column)
    }

    private fun showThanks() {
        val count = helpedStops.size
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Palette.PAPER); setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        column.addView(label("✓", 64, Palette.GREEN, bold = true).apply {
            gravity = Gravity.CENTER; background = rounded(Palette.GREEN_SOFT, 64)
        }, LinearLayout.LayoutParams(dp(128), dp(128)))
        column.addView(label("Thank you!", 34, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) })
        column.addView(label("Your answers help people plan their trip and wait more comfortably.", 18, Palette.MUTED).apply { gravity = Gravity.CENTER })
        column.addView(card { addView(label("You have helped with $count stop${if (count == 1) "" else "s"} so far", 17, bold = true).apply { gravity = Gravity.CENTER }) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        val next = stops.firstOrNull()
        if (next != null) column.addView(primaryButton("Help with the next stop") { openStop(next) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        column.addView(secondaryButton("Back to nearby stops") { showNearby() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        show(Screen.THANKS, column)
    }

    // ---- Finding stops -------------------------------------------------------------------------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationAndLoad() {
        if (hasLocationPermission()) loadStops()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) loadStops()
    }

    private fun loadStops() {
        searchStarted = true
        loading = true
        listNotice = null
        renderStops()
        request?.cancel()
        request = scope.launch {
            try {
                val fix = if (hasLocationPermission()) currentLocation() else null
                locationIsApproximate = fix == null
                searchCenter = fix ?: DOWNTOWN
                declination = GeomagneticField(searchCenter.lat.toFloat(), searchCenter.lon.toFloat(), 1300f, System.currentTimeMillis()).declination
                val client = sessionClient.client
                var found = emptyList<NearbyStop>()
                for (radius in listOf(1_500.0, 5_000.0, 25_000.0, 100_000.0)) {
                    found = withTimeout(30_000) { fetchStops(client, searchCenter, radius) }
                    if (found.size >= 5) break
                }
                stops = found.take(40)
                selectedStopId = stops.firstOrNull()?.taskId
                listNotice = if (stops.isEmpty()) "No stops need help near here right now. Please try again later." else null
                loading = false
                if (screen == Screen.NEARBY) showNearby() else renderStops()
                loadNames(stops.take(15))
            } catch (timedOut: TimeoutCancellationException) {
                loading = false
                listNotice = "Finding stops is taking too long. Check your connection and try again."
                renderStops()
            } catch (cancelled: CancellationException) {
                // A newer search or a new sign-in session replaced this one; whichever replaced it draws the result.
                throw cancelled
            } catch (error: Exception) {
                loading = false
                listNotice = errorText(error)
                renderStops()
            }
        }
    }

    private suspend fun fetchStops(client: MapRouletteClient, center: LatLon, radiusMeters: Double): List<NearbyStop> {
        val (west, south, east, north) = boundsAround(center, radiusMeters)
        val filter = TaskFilter(
            challengeIds = backend.challenges.map { it.challengeId },
            bounds = Bounds(west, south, east, north),
            statuses = listOf(0),
            includeArchived = false,
            choiceOnly = true,
        )
        return client.findTaskMarkers(filter, 200).mapNotNull { marker ->
            val position = pointOf(marker.point) ?: return@mapNotNull null
            NearbyStop(
                taskId = marker.id.value, challengeId = marker.challengeId.value, title = marker.title,
                position = position, distanceMeters = distanceMeters(center, position), bearingDegrees = bearingDegrees(center, position),
            )
        }.sortedBy { it.distanceMeters }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): LatLon? {
        val manager = getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        val lastKnown = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 10 * 60_000) return LatLon(lastKnown.latitude, lastKnown.longitude)
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
                providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        }
        val best = fresh ?: lastKnown ?: return null
        return LatLon(best.latitude, best.longitude)
    }

    /** Looks up each stop's name on OpenStreetMap so the list shows real names. Stops without a name stay "Bus stop". */
    private fun loadNames(list: List<NearbyStop>) {
        list.forEach { stop ->
            scope.launch {
                val name = withContext(Dispatchers.IO) { fetchStopName(stop.title) } ?: return@launch
                stopNames[stop.taskId] = name
                if (screen == Screen.NEARBY) renderStops()
            }
        }
    }

    private fun fetchStopName(title: String): String? {
        val match = Regex("^(node|way)/(\\d+)$").matchEntire(title.trim()) ?: return null
        val (kind, id) = match.destructured
        return try {
            val connection = URL("https://api.openstreetmap.org/api/0.6/$kind/$id.json").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", "UtahStopScout/0.1 (Android)")
            try {
                if (connection.responseCode != 200) return null
                val tags = JSONObject(connection.inputStream.bufferedReader().readText())
                    .getJSONArray("elements").getJSONObject(0).optJSONObject("tags")
                tags?.optString("name")?.takeIf(String::isNotBlank)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Opening and saving a stop -------------------------------------------------------------

    private fun openStop(stop: NearbyStop) = runWork("Getting this stop ready…") {
        val client = sessionClient.client
        val id = TaskId(stop.taskId)
        val task = client.getTask(id)
        if (task.mobileSupport() != MobileSupport.IN_PLACE) return@runWork toast("This stop can't be answered in the app. Please pick another.")
        val eligibility = try {
            client.checkChoice(id)
        } catch (failure: MapRouletteException) {
            if (isBackendWriteRefusal(failure)) return@runWork toast("Answering stops isn't switched on yet, so nothing was changed.")
            throw failure
        }
        if (!eligibility.eligible) return@runWork toast("Someone already answered this stop. Please pick another.")
        val choice = client.work(task) as? TaskWork.Choice ?: return@runWork toast("This stop can't be answered in the app. Please pick another.")
        val questions = filterLiveQuestions(choice.questions, eligibility.questionIds)
        if (questions.isEmpty()) return@runWork toast("This stop already has everything we need. Please pick another.")
        flow = AnswerFlow(stop, task, client, questions)
        showQuestion()
    }

    private fun submit() {
        val current = flow ?: return
        if (current.answers.isEmpty()) return
        runWork("Saving your answers…") {
            try {
                submissionInFlight = true
                withContext(NonCancellable + Dispatchers.IO) {
                    current.client.submitChoice(current.task, ChoiceSubmission.Answers(current.answers.toMap()))
                }
                helpedStops.add(current.stop.taskId)
                stops = stops.filter { it.taskId != current.stop.taskId }
                selectedStopId = stops.firstOrNull()?.taskId
                showThanks()
            } catch (failure: MapRouletteException) {
                if (isBackendWriteRefusal(failure)) toast("Saving isn't switched on yet, so nothing was changed. Your answers are still here.")
                else throw failure
            } finally {
                submissionInFlight = false
            }
        }
    }

    private fun runWork(message: String, block: suspend () -> Unit) {
        if (submissionInFlight) return toast("Please wait a moment.")
        request?.cancel()
        toast(message)
        request = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toast(errorText(error)) }
        }
    }

    private fun errorText(error: Exception) = when (error) {
        is MapRouletteException -> when (error.kind) {
            ErrorKind.NETWORK -> "Can't reach the internet. Check your connection and try again."
            ErrorKind.AUTHENTICATION -> "Please sign in again."
            ErrorKind.NOT_FOUND -> "This stop isn't available any more. Please pick another."
            ErrorKind.PERMISSION -> "Saving isn't switched on yet, so nothing was changed."
            else -> "Something went wrong. Nothing was changed."
        }
        else -> "Something went wrong. Nothing was changed."
    }

    // ---- Account -------------------------------------------------------------------------------

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setNegativeButton("Stay signed in", null)
            .setPositiveButton("Sign out") { _, _ ->
                if (submissionInFlight) toast("Please wait a moment.") else scope.launch { session.signOut() }
            }
            .show()
    }

    private fun showSignInSetup() {
        val message = if (!session.endpoints.enabled) {
            val property = if (backend == Backend.STAGE) "busStopStageClientId" else "busStopProdClientId"
            "No mobile client ID is configured for ${backend.title}. Add `$property=your-client-id` to ~/.gradle/gradle.properties, then register that exact ID as a public mobile client on ${backend.baseUrl}. The backend's OSM app credentials stay server-side."
        } else {
            "Secure credential storage is unavailable on this device, so sign-in cannot start."
        }
        AlertDialog.Builder(this).setTitle("Sign-in setup required").setMessage(message).setPositiveButton("OK", null).show()
    }

    // ---- Map lifecycle -------------------------------------------------------------------------

    private fun buildMapView(): View {
        MapLibre.getInstance(this)
        val view = MapView(this).apply { onCreate(null) }
        mapView = view
        stopMap = StopMap(this, view, searchCenter) { id ->
            selectedStopId = id
            stopMap?.select(id)
            mapCardHolder?.let { holder -> holder.removeAllViews(); holder.addView(mapStopCard()) }
        }
        return view
    }

    private fun attachMap() { mapView?.onStart(); mapView?.onResume() }

    private fun releaseMap() {
        mapView?.let { it.onPause(); it.onStop(); it.onDestroy() }
        mapView = null
        stopMap = null
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume(); if (screen == Screen.NEARBY) startCompass() }
    override fun onPause() { stopCompass(); mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }

    @Deprecated("The activity handles back itself so each screen goes one step back")
    override fun onBackPressed() {
        val current = flow
        when (screen) {
            Screen.QUESTION -> if (current != null) goBackOneQuestion(current) else showNearby()
            Screen.CONFIRM -> if (current != null) { current.index = current.questions.lastIndex; showQuestion() } else showNearby()
            Screen.THANKS -> showNearby()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { signIn.save(outState); super.onSaveInstanceState(outState) }

    @Deprecated("AppAuth uses the Activity result flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        // Cover the screen first: finishing sign-in can change the account state straight away.
        if (resultCode == RESULT_OK) showRiding()
        val handled = signIn.onActivityResult(requestCode, data)
        if (!handled && screen == Screen.RIDING) showWelcome()
    }

    override fun onDestroy() {
        releaseMap()
        request?.cancel(); accountObserver?.cancel(); scope.cancel()
        val closingClient = sessionClient
        if (submissionInFlight && request?.isActive == true) request?.invokeOnCompletion { closingClient.close() }
        else closingClient.close()
        super.onDestroy()
    }

    /** A calm, dark note near the bottom of the screen, in place of the system's red-tinted toast. */
    private fun toast(message: String) {
        val frame = findViewById<ViewGroup>(android.R.id.content) ?: return
        note?.let { frame.removeView(it) }
        @Suppress("DEPRECATION")
        val navigationBar = window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
        val view = label(message, 15, Palette.WHITE).apply {
            background = rounded(Palette.INK, 16)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxWidth = dp(340)
            alpha = 0f
            elevation = dpf(6f)
        }
        note = view
        frame.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navigationBar + dp(28) })
        view.animate().alpha(1f).setDuration(150).start()
        noteTimer?.cancel()
        noteTimer = scope.launch {
            delay(3_200)
            view.animate().alpha(0f).setDuration(200).withEndAction { frame.removeView(view); if (note === view) note = null }.start()
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 41
        private const val SMOOTHING = 0.12f
        private const val MIN_TURN_DEGREES = 2f
        private const val MIN_RIDE_MILLIS = 1_800L
        private const val RIDE_TIMEOUT_MILLIS = 25_000L
        private val DOWNTOWN = LatLon(40.7608, -111.8910)
    }
}

/** `null` means no server filter was returned; an empty set means no questions remain. */
internal fun filterLiveQuestions(questions: List<ChoiceQuestion>, ids: Set<String>?): List<ChoiceQuestion> =
    if (ids == null) questions else questions.filter { it.id in ids }

internal fun accountCanSubmit(view: org.osmutah.utahbusstop.auth.SessionView): Boolean =
    view.canWriteTasks && view.canEditOsm

internal fun isBackendWriteRefusal(error: MapRouletteException): Boolean =
    error.status == 403 || error.kind == ErrorKind.PERMISSION
