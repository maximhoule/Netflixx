package com.maxim.marquee

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Plays YouTube trailers silently behind the home billboard, using YouTube's official
 * embedded player. One WebView is reused for the whole app; switching titles only swaps the
 * video id, so there's no page reload while you browse.
 */
class TrailerPlayer(ctx: Context) {
    /** Null if this device has no working WebView; the feature then simply stays off. */
    val view: WebView? = create(ctx)

    /** True once the current trailer is actually playing (so the artwork can fade away). */
    var playing by mutableStateOf(false)
        private set
    var currentId: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView? = try {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            // Never steal D-pad focus from the rows.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        android.util.Log.w("Marquee", "trailer page: ${m.message()}")
                    }
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            // YouTube's embedded player requires the page to identify the app (HTTP Referer).
            loadDataWithBaseURL("https://com.maxim.marquee", HTML, "text/html", "utf-8", null)
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * Plays the first of [youtubeIds] that YouTube lets us embed (studios often block embedding
     * of their uploads, so the next one is tried automatically).
     */
    fun play(youtubeIds: List<String>, muted: Boolean) {
        val v = view ?: return
        val ids = youtubeIds.map { id -> id.filter { it.isLetterOrDigit() || it == '-' || it == '_' } }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        val key = ids.joinToString(",")
        if (currentId == key) return
        currentId = key
        playing = false
        val arr = ids.joinToString(",", "[", "]") { "'$it'" }
        v.evaluateJavascript("playList($arr, $muted)", null)
    }

    fun stop() {
        val v = view ?: return
        if (currentId == null && !playing) return
        currentId = null
        playing = false
        v.evaluateJavascript("stopTrailer()", null)
    }

    fun onPause() {
        stop()
        view?.onPause()
    }

    fun onResume() {
        view?.onResume()
    }

    fun destroy() {
        runCatching {
            (view?.parent as? ViewGroup)?.removeView(view)
            view?.destroy()
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onError(code: Int, id: String) {
            // 2 bad id · 5 HTML5 error · 100 removed/private · 101/150 embedding not allowed · 153 missing referrer
            android.util.Log.w("Marquee", "trailer $id can't play here (YouTube error $code)")
        }

        @JavascriptInterface
        fun onState(state: Int) {
            if (state == 1) android.util.Log.i("Marquee", "trailer playing")
            // 1 = playing; anything else (buffering, ended, error) hides the video.
            main.post { if (currentId != null) playing = state == 1 }
        }
    }

    private companion object {
        const val HTML = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;height:100%;background:transparent;overflow:hidden}
/* Slightly oversized so YouTube's title bar and logo fall outside the visible area. */
#wrap{position:absolute;top:50%;left:50%;width:124%;height:124%;transform:translate(-50%,-50%);pointer-events:none}
#p{width:100%;height:100%}
</style></head><body>
<div id="wrap"><div id="p"></div></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player=null, ready=false, pending=null;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{width:'100%',height:'100%',
    playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,rel:0,playsinline:1,mute:1,cc_load_policy:0,origin:'https://com.maxim.marquee'},
    events:{
      onReady:function(){ready=true; if(pending){var q=pending; pending=null; playList(q[0],q[1]);}},
      onStateChange:function(e){ Android.onState(e.data); if(e.data===0){player.seekTo(4); player.playVideo();} },
      onError:function(e){ Android.onError(e.data, list[idx]||''); next(); }
    }});
}
var list=[], idx=0, wantMuted=true;
function playList(ids,muted){ list=ids; idx=0; wantMuted=muted; start(); }
function start(){
  if(!ready){pending=[list,wantMuted]; return;}
  if(idx>=list.length){ Android.onState(-2); return; }
  if(wantMuted){player.mute();}else{player.unMute(); player.setVolume(70);}
  player.loadVideoById({videoId:list[idx],startSeconds:4,suggestedQuality:'hd720'});
}
function next(){ idx++; if(idx<list.length){ start(); } else { Android.onState(-2); } }
function stopTrailer(){ pending=null; list=[]; if(ready){player.stopVideo();} }
</script></body></html>"""
    }
}
