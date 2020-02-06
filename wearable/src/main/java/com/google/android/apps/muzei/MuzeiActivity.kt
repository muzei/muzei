package com.google.android.apps.muzei

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.widget.RoundedDrawable
import com.google.android.apps.muzei.datalayer.ActivateMuzeiIntentService
import com.google.android.apps.muzei.datalayer.DataLayerArtProvider
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.filterNotNull
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.DATA_LAYER_AUTHORITY
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiActivityBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MuzeiViewModel(application: Application) : AndroidViewModel(application) {

    val artworkLiveData = MuzeiDatabase.getInstance(application).artworkDao().currentArtwork
}

class MuzeiActivity : FragmentActivity(),
        AmbientModeSupport.AmbientCallbackProvider {
    companion object {
        private const val FACTOR = 0.146467f // c = a * sqrt(2)
    }

    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {
                private val timeFormat12h = SimpleDateFormat("h:mm", Locale.getDefault())
                private val timeFormat24h = SimpleDateFormat("H:mm", Locale.getDefault())

                private var showImageOnExit = false

                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    showImageOnExit = binding.image.isVisible
                    binding.image.isVisible = false
                    binding.time.isVisible = true
                    updateTime()
                }

                override fun onUpdateAmbient() {
                    updateTime()
                }

                fun updateTime() {
                    binding.time.text = if (DateFormat.is24HourFormat(this@MuzeiActivity))
                        timeFormat24h.format(System.currentTimeMillis())
                    else
                        timeFormat12h.format(System.currentTimeMillis())
                }

                override fun onExitAmbient() {
                    binding.image.isVisible = showImageOnExit
                    binding.time.isVisible = false
                }
            }
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    private lateinit var binding: MuzeiActivityBinding

    private val viewModel: MuzeiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        binding = MuzeiActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scrollView.requestFocus()

        if (resources.configuration.isScreenRound) {
            val inset = (FACTOR * Resources.getSystem().displayMetrics.widthPixels).toInt()
            binding.content.setPadding(inset, 0, inset, inset)
        }
        binding.image.setOnClickListener {
            startActivity(Intent(this@MuzeiActivity,
                    FullScreenActivity::class.java))
        }
        binding.nextArtwork.setCompoundDrawablesRelative(RoundedDrawable().apply {
            isClipEnabled = true
            radius = resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
            backgroundColor = ContextCompat.getColor(this@MuzeiActivity,
                    R.color.theme_primary)
            drawable = ContextCompat.getDrawable(this@MuzeiActivity,
                    R.drawable.ic_next_artwork)
            bounds = Rect(0, 0, radius * 2, radius * 2)
        }, null, null, null)
        binding.nextArtwork.setOnClickListener {
            ProviderManager.getInstance(this).nextArtwork()
        }
        binding.openOnPhone.setCompoundDrawablesRelative(RoundedDrawable().apply {
            isClipEnabled = true
            radius = resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
            backgroundColor = ContextCompat.getColor(this@MuzeiActivity,
                    R.color.theme_primary)
            drawable = ContextCompat.getDrawable(this@MuzeiActivity,
                    R.drawable.open_on_phone_button)
            bounds = Rect(0, 0, radius * 2, radius * 2)
        }, null, null, null)
        binding.provider.setOnClickListener {
            startActivity(Intent(this@MuzeiActivity,
                    ChooseProviderActivity::class.java))
        }
        binding.settings.setCompoundDrawablesRelative(RoundedDrawable().apply {
            isClipEnabled = true
            radius = resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
            backgroundColor = ContextCompat.getColor(this@MuzeiActivity,
                    R.color.theme_primary)
            drawable = ContextCompat.getDrawable(this@MuzeiActivity,
                    R.drawable.ic_provider_settings)
            bounds = Rect(0, 0, radius * 2, radius * 2)
        }, null, null, null)

        viewModel.artworkLiveData.filterNotNull().observe(this) { artwork ->
            lifecycleScope.launch(Dispatchers.Main) {
                val image = ImageLoader.decode(
                        contentResolver, artwork.contentUri)
                if (image != null) {
                    binding.image.setImageDrawable(RoundedDrawable().apply {
                        isClipEnabled = true
                        radius = resources.getDimensionPixelSize(R.dimen.art_detail_image_radius)
                        drawable = BitmapDrawable(resources, image)
                    })
                }
                binding.image.contentDescription = artwork.title
                        ?: artwork.byline
                        ?: artwork.attribution
                binding.image.isVisible = image != null && !ambientController.isAmbient
                binding.title.text = artwork.title
                binding.title.isVisible = !artwork.title.isNullOrBlank()
                binding.byline.text = artwork.byline
                binding.byline.isVisible = !artwork.byline.isNullOrBlank()
                binding.attribution.text = artwork.attribution
                binding.attribution.isVisible = !artwork.attribution.isNullOrBlank()
                binding.openOnPhone.setOnClickListener {
                    lifecycleScope.launch {
                        FirebaseAnalytics.getInstance(this@MuzeiActivity).logEvent(
                                FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                                FirebaseAnalytics.Param.ITEM_ID to DataLayerArtProvider.OPEN_ON_PHONE_ACTION,
                                FirebaseAnalytics.Param.ITEM_NAME to getString(R.string.common_open_on_phone),
                                FirebaseAnalytics.Param.ITEM_CATEGORY to "actions",
                                FirebaseAnalytics.Param.CONTENT_TYPE to "wear_activity"))
                        artwork.sendAction(this@MuzeiActivity,
                                DataLayerArtProvider.OPEN_ON_PHONE_ACTION)
                    }
                }
                binding.openOnPhone.isVisible = artwork.providerAuthority == DATA_LAYER_AUTHORITY
            }
        }

        ProviderManager.getInstance(this).observe(this) { provider ->
            if (provider == null) {
                val context = this@MuzeiActivity
                GlobalScope.launch {
                    ProviderManager.select(context, FEATURED_ART_AUTHORITY)
                    ActivateMuzeiIntentService.checkForPhoneApp(context)
                }
                return@observe
            }
            binding.nextArtwork.isVisible = provider.supportsNextArtwork
            val pm = packageManager
            val providerInfo = pm.resolveContentProvider(provider.authority,
                    PackageManager.GET_META_DATA)
            if (providerInfo != null) {
                val size = resources.getDimensionPixelSize(R.dimen.choose_provider_image_size)
                val icon = providerInfo.loadIcon(pm)
                icon.bounds = Rect(0, 0, size, size)
                binding.provider.setCompoundDrawablesRelative(icon,
                        null, null, null)
                binding.provider.text = providerInfo.loadLabel(pm)
                val authority = providerInfo.authority
                lifecycleScope.launch(Dispatchers.Main) {
                    val description = ProviderManager.getDescription(this@MuzeiActivity, authority)
                    binding.providerDescription.isGone = description.isBlank()
                    binding.providerDescription.text = description
                }
                val settingsActivity = providerInfo.metaData?.getString("settingsActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                }
                binding.settings.isVisible = settingsActivity != null
                binding.settings.setOnClickListener {
                    if (settingsActivity != null) {
                        startActivity(Intent().apply {
                            component = settingsActivity
                        })
                    }
                }
            }
        }
        ProviderChangedReceiver.observeForVisibility(this, this)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
