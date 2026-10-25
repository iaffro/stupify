package com.eddyizm.tempus.stupify

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyLoginBinding
import com.eddyizm.tempus.databinding.StupifyRequestAccessBinding
import com.eddyizm.tempus.interfaces.SystemCallback
import com.eddyizm.tempus.model.Server
import com.eddyizm.tempus.repository.PlaylistRepository
import com.eddyizm.tempus.repository.ServerRepository
import com.eddyizm.tempus.repository.SystemRepository
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sign-in screen of the stupify build: the server is built in, so only username (or email) and password are asked.
 * Replaces the view of Tempus' LoginFragment; "Use another server" opens Tempus' own server setup.
 */
object StupifyLogin {
    private const val SERVER_ID = "stupify"

    @JvmStatic
    fun createView(fragment: Fragment, inflater: LayoutInflater, container: ViewGroup?,
                   onSignedIn: Runnable, onOtherServer: Runnable): View {
        val bind = StupifyLoginBinding.inflate(inflater, container, false)
        val activity = fragment.requireActivity()

        // stupify://login?u=<username> from the welcome page, else the last account used here
        val linked = activity.intent?.data?.takeIf { it.scheme == "stupify" }?.getQueryParameter("u")
        bind.stupifyUser.setText(linked ?: Preferences.getUser().orEmpty())
        if (!bind.stupifyUser.text.isNullOrEmpty()) bind.stupifyPassword.requestFocus()

        val signIn = { signIn(fragment, bind, onSignedIn) }
        bind.stupifySignIn.setOnClickListener { signIn() }
        bind.stupifyPassword.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { signIn(); true } else false
        }
        bind.stupifyRequestAccess.setOnClickListener { requestAccess(fragment, bind) }
        bind.stupifyOtherServer.setOnClickListener { onOtherServer.run() }
        return bind.root
    }

    private fun signIn(fragment: Fragment, bind: StupifyLoginBinding, onSignedIn: Runnable) {
        val login = bind.stupifyUser.text.toString().trim().lowercase()
        val password = bind.stupifyPassword.text.toString()
        if (login.isEmpty() || password.isEmpty()) {
            Toast.makeText(fragment.context, R.string.stupify_login_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if ("@" !in login) return signIn(fragment, bind, onSignedIn, login, password)
        // an email: the hub looks up the username (checking the password), then sign in as usual
        setBusy(bind, true)
        fragment.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { usernameFor(login, password) } }
            if (!fragment.isAdded) return@launch
            val user = result.getOrNull()
            if (user != null) return@launch signIn(fragment, bind, onSignedIn, user, password)
            setBusy(bind, false)
            if (result.isFailure) {
                Toast.makeText(fragment.context, R.string.stupify_login_offline, Toast.LENGTH_LONG).show()
                return@launch
            }
            val state = withContext(Dispatchers.IO) { runCatching { requestState(login) }.getOrNull() }
            val message = when (state) {
                "pending" -> R.string.stupify_login_pending
                "rejected" -> R.string.stupify_login_rejected
                else -> null
            }
            if (!fragment.isAdded) return@launch
            if (message == null) {
                Toast.makeText(fragment.context, R.string.stupify_login_failed, Toast.LENGTH_LONG).show()
            } else {
                MaterialAlertDialogBuilder(fragment.requireActivity())
                    .setTitle(R.string.stupify_request_title)
                    .setMessage(fragment.getString(message, login))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun signIn(fragment: Fragment, bind: StupifyLoginBinding, onSignedIn: Runnable,
                       user: String, password: String) {
        val server = Server(
            serverId = SERVER_ID, serverName = "Stupify", username = user, password = password,
            address = BuildConfig.STUPIFY_SERVER, localAddress = "", timestamp = System.currentTimeMillis(),
            isLowSecurity = false, clientCert = "", customHeaders = null
        )
        setBusy(bind, true)
        // same preferences LoginFragment writes when a saved server is tapped
        Preferences.setServerId(server.serverId)
        Preferences.setServer(server.address)
        Preferences.setLocalAddress(server.address)
        Preferences.setInUseServerAddress(server.address)
        Preferences.setUser(user)
        Preferences.setPassword(password)
        Preferences.setLowSecurity(false)
        Preferences.setClientCert(null)
        Preferences.setCustomHeaders(null)
        App.getSubsonicClientInstance(true)

        SystemRepository().checkUserCredential(object : SystemCallback {
            override fun onSuccess(password: String?, token: String?, salt: String?) {
                ServerRepository().insert(server)
                PlaylistRepository().refreshAllPlaylists()
                if (fragment.isAdded) onSignedIn.run()
            }

            override fun onNetworkFailure(exception: Exception) = failed(R.string.stupify_login_offline)

            override fun onError(exception: Exception) {
                failed(R.string.stupify_login_failed)
                // wrong password, or a request that hasn't been approved yet?
                fragment.lifecycleScope.launch {
                    val state = withContext(Dispatchers.IO) { runCatching { requestState(user) }.getOrNull() }
                    val message = when (state) {
                        "pending" -> R.string.stupify_login_pending
                        "rejected" -> R.string.stupify_login_rejected
                        else -> return@launch
                    }
                    if (fragment.isAdded) {
                        MaterialAlertDialogBuilder(fragment.requireActivity())
                            .setTitle(R.string.stupify_request_title)
                            .setMessage(fragment.getString(message, user))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }

            private fun failed(message: Int) {
                Preferences.setServerId(null)
                Preferences.setServer(null)
                Preferences.setPassword(null)
                Preferences.setToken(null)
                Preferences.setSalt(null)
                App.getSubsonicClientInstance(true)
                if (!fragment.isAdded) return
                setBusy(bind, false)
                Toast.makeText(fragment.context, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    /** Username for an email sign-in; null when the email or password is wrong. Throws when the hub can't be reached. */
    private fun usernameFor(email: String, password: String): String? {
        val conn = URL(BuildConfig.STUPIFY_SERVER + "/app/v1/sign-in-name").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("login", email).put("password", password)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().readText()).getString("username")
                401 -> null
                else -> throw IOException("hub said ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun setBusy(bind: StupifyLoginBinding, busy: Boolean) {
        bind.stupifySignIn.isEnabled = !busy
        bind.stupifyProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun requestState(user: String): String? {
        val url = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/register/state?username=" + URLEncoder.encode(user, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("state").takeIf { it.isNotEmpty() && it != "null" }
        } finally {
            conn.disconnect()
        }
    }

    private fun requestAccess(fragment: Fragment, login: StupifyLoginBinding) {
        val activity = fragment.requireActivity()
        val form = StupifyRequestAccessBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.stupify_request_title)
            .setView(form.root)
            .setPositiveButton(R.string.stupify_request_send, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        send.setOnClickListener {
            val password = form.stupifyReqPassword.text.toString()
            if (password.length < 8) {
                Toast.makeText(activity, R.string.stupify_request_password_short, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (password != form.stupifyReqPassword2.text.toString()) {
                Toast.makeText(activity, R.string.stupify_request_password_mismatch, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val username = form.stupifyReqUsername.text.toString().trim().lowercase()
            val body = JSONObject()
                .put("name", form.stupifyReqName.text.toString())
                .put("username", form.stupifyReqUsername.text.toString())
                .put("email", form.stupifyReqEmail.text.toString())
                .put("spotify_email", form.stupifyReqSpotifyEmail.text.toString())
                .put("note", form.stupifyReqNote.text.toString())
                .put("password", password)
            send.isEnabled = false
            fragment.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) { postRegister(activity, body) }
                send.isEnabled = true
                if (error == null) {
                    dialog.dismiss()
                    login.stupifyUser.setText(username)  // ready to sign in once approved
                    login.stupifyPassword.setText("")
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.stupify_request_title)
                        .setMessage(activity.getString(R.string.stupify_request_sent, username))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Returns null when the request was stored, else the message to show. */
    private fun postRegister(activity: Activity, body: JSONObject): String? {
        val conn = URL(BuildConfig.STUPIFY_SERVER + "/app/v1/register").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 200) return null
            val detail = conn.errorStream?.bufferedReader()?.readText()
                ?.let { runCatching { JSONObject(it).get("detail") }.getOrNull() }
            // FastAPI validation errors come back as a list; the hub's own errors are plain messages
            (detail as? String) ?: "Couldn't send the request (${conn.responseCode})"
        } catch (e: IOException) {
            activity.getString(R.string.stupify_login_offline)
        } finally {
            conn.disconnect()
        }
    }
}
