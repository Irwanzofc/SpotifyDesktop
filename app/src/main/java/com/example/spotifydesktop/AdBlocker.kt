package com.example.spotifydesktop

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** "Extension" ad blocker bawaan: blokir request iklan + sembunyikan elemen iklan + bisukan audio iklan. */
object AdBlocker {
    // Domain iklan/pelacak pihak ketiga. Tambahkan sendiri sesuai kebutuhan.
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "moatads.com", "scorecardresearch.com", "adsrvr.org", "adnxs.com", "pubmatic.com",
        "criteo.com", "adeventtracker.spotify.com"
    )

    // Path iklan di domain spotify.com. Jika musik jadi berhenti/error, hapus path di sini.
    private val spotifyAdPaths = listOf("/ads/", "/ad-logic/", "/pagead/", "/ad-state-machine/")

    fun shouldBlock(u: Uri): Boolean {
        val host = u.host ?: return false
        if (adHosts.any { host == it || host.endsWith(".$it") }) return true
        val path = u.path ?: ""
        return host.endsWith("spotify.com") && spotifyAdPaths.any { path.startsWith(it) }
    }

    fun blocked() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    // JS yang disuntik ke halaman: paksa lebar desktop, sembunyikan banner iklan, bisukan audio saat iklan.
    // Selector/teks deteksi iklan bisa berubah kapan saja saat Spotify memperbarui webnya.
    const val SCRIPT = """(function(){
if(window.__spd)return;window.__spd=1;
function vp(){try{
 var m=document.querySelector('meta[name=viewport]');
 if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
 if(m.content!=='width=1280')m.content='width=1280';
}catch(e){}}
var css='[data-testid*="adslot" i],[data-testid="leaderboard-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],[aria-label="Advertisement" i]{display:none!important}';
var wasAd=false;
setInterval(function(){
 try{
  vp();
  if(!document.getElementById('spd-css')){var s=document.createElement('style');s.id='spd-css';s.textContent=css;(document.head||document.documentElement).appendChild(s);}
  var w=document.querySelector('[data-testid="now-playing-widget"]');
  var t=document.title+' '+(w?((w.getAttribute('aria-label')||'')+' '+w.textContent):'');
  var ad=/advertisement|iklan/i.test(t);
  document.querySelectorAll('audio,video').forEach(function(e){
   if(ad){e.muted=true;}else if(wasAd){e.muted=false;}
  });
  wasAd=ad;
 }catch(e){}
},500);
})();"""
}
