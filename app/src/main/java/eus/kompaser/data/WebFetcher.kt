package eus.kompaser.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Kompaser"

/**
 * Descarga el HTML original de una página. Primero con una petición normal; si la web la rechaza
 * (antibots como Akamai), carga la página en un WebView invisible —un navegador real— y desde
 * dentro vuelve a pedir el HTML con fetch() para obtenerlo sin modificar por los scripts.
 */
class WebFetcher(private val context: Context) {
	suspend fun html(url: String, accept: (String) -> Boolean): String {
		val direct = withContext(Dispatchers.IO) {
			runCatching { Http.get(url) }.onFailure { Log.i(TAG, "Petición directa fallida: ${it.message}") }.getOrNull()
		}
		if (direct != null && accept(direct)) return direct
		Log.i(TAG, "Probando con navegador interno: $url")
		val viaBrowser = withTimeoutOrNull(40_000) { withContext(Dispatchers.Main) { browser(url) } }
			?: error("La web tarda demasiado en responder")
		if (!accept(viaBrowser)) {
			Log.w(TAG, "Respuesta no válida (${viaBrowser.length} bytes): ${viaBrowser.take(300)}")
			runCatching { java.io.File(context.getExternalFilesDir(null), "ultima-pagina.html").writeText(viaBrowser) }
			error(if ("Access Denied" in viaBrowser) "La web ha bloqueado el acceso desde esta red" else "La página no contiene acordes")
		}
		Log.i(TAG, "HTML obtenido con el navegador interno (${viaBrowser.length} bytes)")
		return viaBrowser
	}

	@SuppressLint("SetJavaScriptEnabled")
	private suspend fun browser(url: String): String = suspendCancellableCoroutine { cont ->
		val site = siteOf(Uri.parse(url).host.orEmpty())
		val web = WebView(context)
		val handler = Handler(Looper.getMainLooper())
		var done = false
		var asked = false
		fun finish(r: Result<String>) = handler.post {
			if (done) return@post
			done = true
			handler.removeCallbacksAndMessages(null)
			web.destroy()
			r.fold(cont::resume, cont::resumeWithException)
		}
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.settings.blockNetworkImage = true
		web.addJavascriptInterface(object {
			@JavascriptInterface
			fun ok(html: String) = finish(Result.success(html)).let { }

			@JavascriptInterface
			fun fail(msg: String) = finish(Result.failure(IllegalStateException(msg))).let { }
		}, "Kompaser")
		web.webViewClient = object : WebViewClient() {
			// Solo se cargan recursos de la propia web: fuera publicidad y analítica (la página carga mucho antes).
			override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
				val host = req.url.host.orEmpty()
				return if (siteOf(host) == site) null else WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
			}
		}
		// En cuanto el documento está listo se pide el HTML original (sin esperar a que acabe de cargar todo).
		val poll = object : Runnable {
			override fun run() {
				if (done) return
				web.evaluateJavascript("document.readyState") { state ->
					Log.d(TAG, "readyState=$state")
					if (!asked && (state == "\"interactive\"" || state == "\"complete\"")) {
						asked = true
						web.evaluateJavascript(
							"""fetch(location.href, {credentials: 'include'})
								.then(r => r.text().then(t => Kompaser.ok(t)))
								.catch(e => Kompaser.fail(String(e)))""",
							null,
						)
					}
				}
				handler.postDelayed(this, 1000)
			}
		}
		cont.invokeOnCancellation { finish(Result.failure(it ?: IllegalStateException("cancelado"))) }
		web.loadUrl(url)
		handler.postDelayed(poll, 1000)
	}

	/** Dominio registrable aproximado: "www.cifraclub.com.br" → "cifraclub". */
	private fun siteOf(host: String): String {
		val parts = host.split('.').filter { it.isNotEmpty() && it !in setOf("www", "m", "es", "com", "br", "net", "org", "co") }
		return parts.lastOrNull().orEmpty()
	}
}
