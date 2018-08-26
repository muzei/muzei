package com.google.android.apps.muzei

import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.ViewModelProvider
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.support.wear.ambient.AmbientModeSupport
import android.support.wear.widget.RoundedDrawable
import android.text.format.DateFormat
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observeNonNull
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import java.text.SimpleDateFormat
import java.util.Locale

class MuzeiViewModel(application: Application) : AndroidViewModel(application) {

    val artworkLiveData = MuzeiDatabase.getInstance(application).artworkDao().currentArtwork
}

class MuzeiActivity : FragmentActivity(),
        AmbientModeSupport.AmbientCallbackProvider {
    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {
                private val timeFormat12h = SimpleDateFormat("h:mm", Locale.getDefault())
                private val timeFormat24h = SimpleDateFormat("H:mm", Locale.getDefault())

                private var showImageOnExit = false

                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    showImageOnExit = imageView.isVisible
                    imageView.isVisible = false
                    timeView.isVisible = true
                    updateTime()
                }

                override fun onUpdateAmbient() {
                    updateTime()
                }

                fun updateTime() {
                    timeView.text = if (DateFormat.is24HourFormat(this@MuzeiActivity))
                        timeFormat24h.format(System.currentTimeMillis())
                    else
                        timeFormat12h.format(System.currentTimeMillis())
                }

                override fun onExitAmbient() {
                    imageView.isVisible = showImageOnExit
                    timeView.isVisible = false
                }
            }
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    private lateinit var timeView: TextView
    private lateinit var imageView: ImageView
    private lateinit var titleView: TextView
    private lateinit var bylineView: TextView
    private lateinit var attributionView: TextView

    private val viewModelProvider by lazy {
        ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application))
    }
    private val viewModel by lazy {
        viewModelProvider[MuzeiViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        setContentView(R.layout.muzei_activity)
        timeView = findViewById(R.id.time)
        imageView = findViewById(R.id.image)
        titleView = findViewById(R.id.title)
        bylineView = findViewById(R.id.byline)
        attributionView = findViewById(R.id.attribution)

        imageView.setOnClickListener {
            startActivity(Intent(this@MuzeiActivity,
                    FullScreenActivity::class.java))
        }

        viewModel.artworkLiveData.observeNonNull(this) { artwork ->
            launch(UI) {
                val image = ImageLoader.decode(
                        contentResolver, artwork.contentUri)
                if (image != null) {
                    imageView.setImageDrawable(RoundedDrawable().apply {
                        isClipEnabled = true
                        radius = resources.getDimensionPixelSize(R.dimen.art_detail_image_radius)
                        drawable = BitmapDrawable(resources, image)
                    })
                }
                imageView.contentDescription = artwork.title
                        ?: artwork.byline
                        ?: artwork.attribution
                imageView.isVisible = image != null && !ambientController.isAmbient
                titleView.text = artwork.title
                titleView.isVisible = !artwork.title.isNullOrBlank()
                bylineView.text = artwork.byline
                bylineView.isVisible = !artwork.byline.isNullOrBlank()
                attributionView.text = artwork.attribution
                attributionView.isVisible = !artwork.attribution.isNullOrBlank()
            }
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
