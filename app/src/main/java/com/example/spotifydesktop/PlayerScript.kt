package com.example.spotifydesktop

/** JS yang disuntik ke web player: lebar halaman, sembunyikan iklan, bisukan iklan, dan kirim info lagu ke Android. */
object PlayerScript {
    const val SCRIPT = """(function(){
if(window.__spd)return;window.__spd=1;
function vp(){try{
 var w='width='+(window.__vpw||768);
 var m=document.querySelector('meta[name=viewport]');
 if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
 if(m.content!==w)m.content=w;
}catch(e){}}
function q(s){return document.querySelector(s);}
function tx(s){var e=q(s);return e?e.textContent:'';}
var css='[data-testid*="adslot" i],[data-testid="leaderboard-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],[aria-label="Advertisement" i]{display:none!important}';
var wasAd=false,last='',prev=new WeakMap(),stuck=0;
setInterval(function(){
 try{
  vp();
  if(!document.getElementById('spd-css')){var s=document.createElement('style');s.id='spd-css';s.textContent=css;(document.head||document.documentElement).appendChild(s);}
  var w=q('[data-testid="now-playing-widget"]');
  var t=document.title+' '+(w?((w.getAttribute('aria-label')||'')+' '+w.textContent):'');
  var ad=/advertisement|iklan/i.test(t);
  document.querySelectorAll('audio,video').forEach(function(e){if(ad){e.muted=true;}else if(wasAd){e.muted=false;}});
  wasAd=ad;
  var md=navigator.mediaSession&&navigator.mediaSession.metadata;
  var title=md&&md.title?md.title:tx('[data-testid="context-item-link"]');
  var artist=md&&md.artist?md.artist:tx('[data-testid="context-item-info-artist"]');
  var art=md&&md.artwork&&md.artwork.length?md.artwork[md.artwork.length-1].src:'';
  var st=navigator.mediaSession?navigator.mediaSession.playbackState:'';
  var pp=q('[data-testid="control-button-playpause"]');
  var playing=st==='playing'?true:(st==='paused'?false:(pp?/pause|jeda/i.test(pp.getAttribute('aria-label')||''):false));
  var els=document.querySelectorAll('audio,video'),adv=false;
  els.forEach(function(e){if(prev.get(e)!==e.currentTime)adv=true;prev.set(e,e.currentTime);});
  if(playing&&!ad&&els.length){stuck=adv?0:stuck+1;if(stuck>=24)playing=false;}else{stuck=0;}
  var key=title+'|'+artist+'|'+art+'|'+playing;
  if(key!==last&&window.SpotifyBridge){last=key;SpotifyBridge.update(title,artist,art,playing);}
 }catch(e){}
},500);
})();"""
}
