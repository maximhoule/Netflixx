# FFmpeg audio decoder (Jellyfin build of the Media3 extension).
# Its AAR doesn't ship these rules, so they're copied from androidx/media decoder_ffmpeg.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
  private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
# Created by reflection in DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }

# Keep readable stack traces.
-keepattributes SourceFile,LineNumberTable

# Trailer WebView bridge (called from JavaScript).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Our own classes keep their names (still optimised and shrunk) so the baseline profile in
# src/main/baseline-prof.txt matches them after R8.
-keepnames class com.maxim.marquee.** { *; }
