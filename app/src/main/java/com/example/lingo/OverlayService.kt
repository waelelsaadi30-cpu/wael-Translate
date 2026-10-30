package com.example.lingo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.hypot
import kotlin.math.max

class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var layer: View? = null
    private var popup: TextView? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    @Volatile private var latest: Bitmap? = null
    private var sx = 0f
    private var sy = 0f

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
        )
    }
    private val hold = Runnable { onHold(sx, sy) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNow()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(intent.getIntExtra("code", Activity.RESULT_OK), data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        setupCapture()
        showLayer()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("t", "الترجمة", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "t")
            .setContentTitle("الترجمة شغالة")
            .setContentText("اضغط إيقاف عند الانتهاء")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "إيقاف", pi
                ).build()
            ).build()
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture() {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val p = img.planes[0]
                val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
                if (crop !== bmp) bmp.recycle()
                latest = crop
            } finally { img.close() }
        }, handler)
        reader = r
        vdisplay = projection?.createVirtualDisplay(
            "cap", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun showLayer() {
        val v = View(this)
        v.setBackgroundColor(0x12000000)
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY
                    handler.postDelayed(hold, 1500)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.rawX - sx, e.rawY - sy) > 40) handler.removeCallbacks(hold)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(hold)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        layer = v
    }

    private fun onHold(x: Float, y: Float) {
        removePopup()
        handler.postDelayed({
            val bmp = latest ?: return@postDelayed
            recognizer.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { t ->
                var best: String? = null
                var bd = Float.MAX_VALUE
                for (b in t.textBlocks) for (l in b.lines) for (e in l.elements) {
                    val r = e.boundingBox ?: continue
                    val dx = max(max(r.left - x, x - r.right), 0f)
                    val dy = max(max(r.top - y, y - r.bottom), 0f)
                    val d = hypot(dx, dy)
                    if (d < bd) { bd = d; best = e.text }
                }
                val word = best?.trim { !it.isLetterOrDigit() }
                if (word.isNullOrEmpty() || bd > 80f) show("لم أجد كلمة هنا", x, y)
                else translate(word, x, y)
            }.addOnFailureListener { show("تعذّرت قراءة الشاشة", x, y) }
        }, 400)
    }

    private fun translate(word: String, x: Float, y: Float) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(word)
                    .addOnSuccessListener { show("$word\n$it", x, y) }
                    .addOnFailureListener { show("فشلت الترجمة", x, y) }
            }
            .addOnFailureListener { show("تأكد من الإنترنت لتنزيل نموذج الترجمة", x, y) }
    }

    private fun show(text: String, x: Float, y: Float) {
        removePopup()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                setColor(0xEE222222.toInt()); cornerRadius = 24f
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = max(0, x.toInt() - 120)
        lp.y = y.toInt() + 80
        wm.addView(tv, lp)
        popup = tv
        handler.postDelayed({ removePopup() }, 4000)
    }

    private fun removePopup() {
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popup = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removePopup()
        layer?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
