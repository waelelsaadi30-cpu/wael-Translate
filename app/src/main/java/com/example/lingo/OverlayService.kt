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
import android.media.Image
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
    private var bubble: TextView? = null
    private var popup: TextView? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var screenW = 0
    private var screenH = 0
    private var candidate: Bitmap? = null

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
        )
    }

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
        showBubble()
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
            .setContentText("اسحب الفقاعة فوق الكلمة. اضغط إيقاف عند الانتهاء")
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
        screenW = m.widthPixels
        screenH = m.heightPixels
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vdisplay = projection?.createVirtualDisplay(
            "cap", screenW, screenH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, screenH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        val crop = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
        if (crop !== bmp) bmp.recycle()
        return crop
    }

    private fun showBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "ت"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xBB1565C0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 30
        lp.y = 500
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    removePopup()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (hypot(dx, dy) > 15) moved = true
                    lp.x = ix + dx.toInt()
                    lp.y = iy + dy.toInt()
                    wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> if (moved) {
                    capture((lp.x + size / 2).toFloat(), (lp.y + size / 2).toFloat())
                }
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun capture(x: Float, y: Float) {
        val r = reader ?: return
        try {
            while (true) { (r.acquireNextImage() ?: break).close() }
        } catch (_: Exception) {}
        candidate = null
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try { candidate = toBitmap(img) } finally { img.close() }
            } catch (_: Exception) {}
        }, handler)
        bubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            r.setOnImageAvailableListener(null, null)
            bubble?.visibility = View.VISIBLE
            val bmp = candidate
            if (bmp == null) show("تعذّر التقاط الشاشة، جرّب تاني", x, y)
            else ocr(bmp, x, y)
        }, 450)
    }

    private fun ocr(bmp: Bitmap, x: Float, y: Float) {
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
            if (word.isNullOrEmpty() || bd > 90f) show("لم أجد كلمة هنا", x, y)
            else translate(word, x, y)
        }.addOnFailureListener { show("تعذّرت قراءة الشاشة", x, y) }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = max(0, x.toInt() - 120)
        lp.y = y.toInt() + 90
        wm.addView(tv, lp)
        popup = tv
        handler.postDelayed({ removePopup() }, 5000)
    }

    private fun removePopup() {
        popup?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        popup = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removePopup()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
