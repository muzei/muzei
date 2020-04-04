package com.google.android.apps.muzei

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.widget.RoundedDrawable
import coil.api.load
import com.google.android.apps.muzei.datalayer.ActivateMuzeiIntentService
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.filterNotNull
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
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

    private val timeFormat12h = SimpleDateFormat("h:mm", Locale.getDefault())
    private val timeFormat24h = SimpleDateFormat("H:mm", Locale.getDefault())

    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {

                override fun onEnterAmbient(ambientDetails: Bundle?) {
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
                    binding.time.isVisible = false
                }
            }
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    private lateinit var binding: MuzeiActivityBinding

    private val viewModel: MuzeiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        binding = MuzeiActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scrollView.requestFocus()

        if (resources.configuration.isScreenRound) {
            val inset = (FACTOR * Resources.getSystem().displayMetrics.widthPixels).toInt()
            binding.content.setPadding(inset, 0, inset, inset)
        }
        binding.artworkInfo.image.setOnClickListener {
            startActivity(Intent(this@MuzeiActivity,
                    FullScreenActivity::class.java))
        }
        binding.nextArtwork.nextArtwork.setCompoundDrawablesRelative(RoundedDrawable().apply {
            isClipEnabled = true
            radius = resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
            backgroundColor = ContextCompat.getColor(this@MuzeiActivity,
                    R.color.theme_primary)
            drawable = ContextCompat.getDrawable(this@MuzeiActivity,
                    R.drawable.ic_next_artwork)
            bounds = Rect(0, 0, radius * 2, radius * 2)
        }, null, null, null)
        binding.nextArtwork.nextArtwork.setOnClickListener {
            ProviderManager.getInstance(this).nextArtwork()
        }
        binding.providerInfo.provider.setOnClickListener {
            startActivity(Intent(this@MuzeiActivity,
                    ChooseProviderActivity::class.java))
        }
        binding.providerInfo.settings.setCompoundDrawablesRelative(RoundedDrawable().apply {
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
                binding.artworkInfo.image.load(artwork.contentUri) {
                    allowHardware(false)
                    target { image ->
                        binding.artworkInfo.image.setImageDrawable(RoundedDrawable().apply {
                            isClipEnabled = true
                            radius = resources.getDimensionPixelSize(R.dimen.art_detail_image_radius)
                            drawable = image
                        })
                    }
                    listener(
                            onError = { _, _ -> binding.artworkInfo.image.isVisible = false },
                            onSuccess = { _, _ -> binding.artworkInfo.image.isVisible = true }
                    )
                }
                binding.artworkInfo.image.contentDescription = artwork.title
                        ?: artwork.byline
                        ?: artwork.attribution
                binding.artworkInfo.title.text = artwork.title
                binding.artworkInfo.title.isVisible = !artwork.title.isNullOrBlank()
                binding.artworkInfo.byline.text = artwork.byline
                binding.artworkInfo.byline.isVisible = !artwork.byline.isNullOrBlank()
                binding.artworkInfo.attribution.text = artwork.attribution
                binding.artworkInfo.attribution.isVisible = !artwork.attribution.isNullOrBlank()
                val commands = artwork.getCommands(this@MuzeiActivity)
                // TODO Show multiple commands rather than only the first
                val command = commands.filterNot { action ->
                    action.title.isBlank()
                }.firstOrNull { action ->
                    action.shouldShowIcon()
                }
                if (command != null) {
                    binding.command.command.text = command.title
                    binding.command.command.setCompoundDrawablesRelative(RoundedDrawable().apply {
                        isClipEnabled = true
                        radius = resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
                        backgroundColor = ContextCompat.getColor(this@MuzeiActivity,
                                R.color.theme_primary)
                        drawable = command.icon.loadDrawable(this@MuzeiActivity)
                        bounds = Rect(0, 0, radius * 2, radius * 2)
                    }, null, null, null)
                    binding.command.command.setOnClickListener {
                        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                            param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
                            param(FirebaseAnalytics.Param.ITEM_NAME, command.title.toString())
                            param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                            param(FirebaseAnalytics.Param.CONTENT_TYPE, "wear_activity")
                        }
                        command.actionIntent.send()
                    }
                    binding.command.command.isVisible = true
                } else {
                    binding.command.command.isVisible = false
                }
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
            binding.nextArtwork.nextArtwork.isVisible = provider.supportsNextArtwork
            val pm = packageManager
            val providerInfo = pm.resolveContentProvider(provider.authority,
                    PackageManager.GET_META_DATA)
            if (providerInfo != null) {
                val size = resources.getDimensionPixelSize(R.dimen.choose_provider_image_size)
                val icon = providerInfo.loadIcon(pm)
                icon.bounds = Rect(0, 0, size, size)
                binding.providerInfo.provider.setCompoundDrawablesRelative(icon,
                        null, null, null)
                binding.providerInfo.provider.text = providerInfo.loadLabel(pm)
                val authority = providerInfo.authority
                lifecycleScope.launch(Dispatchers.Main) {
                    val description = ProviderManager.getDescription(this@MuzeiActivity, authority)
                    binding.providerInfo.providerDescription.isGone = description.isBlank()
                    binding.providerInfo.providerDescription.text = description
                }
                val settingsActivity = providerInfo.metaData?.getString("settingsActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                }
                binding.providerInfo.settings.isVisible = settingsActivity != null
                binding.providerInfo.settings.setOnClickListener {
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
