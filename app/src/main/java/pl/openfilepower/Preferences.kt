package pl.openfilepower

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nas-passwords", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nas-passwords", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size))) }
    }
    fun roots(): List<Root> = runCatching {
        val array = JSONArray(prefs.getString("roots", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).run { Root(getString("id"), getString("name"), getString("address"), optString("user"), optString("domain"), decrypt(optString("secret"))) } }
    }.getOrDefault(emptyList())
    fun saveRoots(roots: List<Root>) {
        val array = JSONArray()
        roots.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("user", it.user).put("domain", it.domain).put("secret", encrypt(it.password))) }
        prefs.edit().putString("roots", array.toString()).apply()
    }
    private fun encode(place: Place) = JSONObject().put("root", place.root).put("parts", JSONArray(place.parts))
    private fun decode(o: JSONObject) = Place(o.getString("root"), o.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } })
    fun places(key: String): List<Place> = runCatching { JSONArray(prefs.getString(key, "[]")).let { a -> (0 until a.length()).map { decode(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun savePlaces(key: String, places: List<Place>) { prefs.edit().putString(key, JSONArray().also { a -> places.forEach { a.put(encode(it)) } }.toString()).apply() }
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(value) { prefs.edit().putBoolean("autoUpdate", value).apply() }
}
