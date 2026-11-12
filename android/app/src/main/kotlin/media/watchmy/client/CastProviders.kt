package media.watchmy.client

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import media.watchmy.client.core.cast.CastConnectReceiver
import media.watchmy.client.core.cast.RealCastService

/**
 * The Cast SDK reads its options from these (named in the manifest). They
 * are only asked for once the app has started casting, which it does only
 * when this build has a receiver App ID (`-PcastAppId`).
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = RealCastService.options(BuildConfig.CAST_APP_ID, MainActivity::class.java.name)

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Cast Connect on Android TV: casts to this app instead of the web receiver. */
class CastReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions = CastConnectReceiver.options(context, BuildConfig.CAST_APP_ID)
}
