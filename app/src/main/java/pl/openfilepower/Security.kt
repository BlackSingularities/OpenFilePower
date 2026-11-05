package pl.openfilepower

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Blokada aplikacji: po powrocie z tła (dłużej niż [GRACE_MS]) aplikacja wymaga odcisku palca,
 * twarzy albo PIN-u/wzoru urządzenia. Wspólna dla głównego okna i okna dla innych aplikacji.
 */
object AppLock {
    const val GRACE_MS = 10_000L
    val locked = MutableStateFlow(false)
    /** Zmienia się przy każdej blokadzie – model zamyka wtedy folder prywatny. */
    val epoch = MutableStateFlow(0)
    private var stoppedAt = 0L
    private var started = false

    fun enabled(context: Context) = Preferences(context).flag("appLock", false)
    fun onStart(context: Context) {
        val away = stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > GRACE_MS
        if (!started || away) {
            if (enabled(context)) locked.value = true
            if (started) epoch.value++
        }
        started = true; stoppedAt = 0
    }
    fun onStop() { stoppedAt = SystemClock.elapsedRealtime() }

    /** Czy urządzenie ma ustawioną biometrię lub blokadę ekranu. */
    fun available(context: Context) = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null, onError: (String) -> Unit = {}, onSuccess: () -> Unit) {
        if (!available(activity)) { onError("Ustaw na urządzeniu odcisk palca lub blokadę ekranu (PIN, wzór, hasło)"); return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED) onError(errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).apply { subtitle?.let { setSubtitle(it) } }.setAllowedAuthenticators(AUTHENTICATORS).build()
        prompt.authenticate(info)
    }
}

/**
 * Folder prywatny: pliki w wewnętrznej pamięci aplikacji (niedostępnej dla innych aplikacji, galerii i komputera po USB),
 * szyfrowanej przez system razem z danymi aplikacji. Widoczny dopiero po uwierzytelnieniu.
 */
object Vault {
    const val ROOT_ID = "vault"
    fun folder(context: Context) = File(context.filesDir, "vault").apply { mkdirs(); File(this, ".nomedia").takeIf { !it.exists() }?.createNewFile() }
    fun root(context: Context) = Root(ROOT_ID, "Folder prywatny", android.net.Uri.fromFile(folder(context)).toString())
}
