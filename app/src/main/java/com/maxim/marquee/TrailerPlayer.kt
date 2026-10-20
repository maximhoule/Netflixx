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
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "Android")
            // YouTube's embedded player requires the page to identify the app (HTTP Referer).
            loadDataWithBaseURL("https://com.maxim.marquee", HTML, "text/html", "utf-8", null)
        }
    } catch (e: Throwable) {
        null
    }

    fun play(youtubeId: String, muted: Boolean) {
        val v = view ?: return
        if (currentId == youtubeId) return
        currentId = youtubeId
        playing = false
        val id = youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        v.evaluateJavascript("playTrailer('$id', $muted)", null)
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

    private inner class Bridge {
        @JavascriptInterface
        fun onState(state: Int) {
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
      onReady:function(){ready=true; if(pending){var q=pending; pending=null; playTrailer(q[0],q[1]);}},
      onStateChange:function(e){ Android.onState(e.data); if(e.data===0){player.seekTo(4); player.playVideo();} },
      onError:function(){ Android.onState(-2); }
    }});
}
function playTrailer(id,muted){
  if(!ready){pending=[id,muted]; return;}
  if(muted){player.mute();}else{player.unMute(); player.setVolume(70);}
  player.loadVideoById({videoId:id,startSeconds:4,suggestedQuality:'hd720'});
}
function stopTrailer(){ pending=null; if(ready){player.stopVideo();} }
</script></body></html>"""
    }
}
