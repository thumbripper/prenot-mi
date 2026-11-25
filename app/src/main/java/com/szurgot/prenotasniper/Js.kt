package com.szurgot.prenotasniper

/**
 * All JavaScript injected into the Prenot@mi pages. Selectors are based on the real DOM recorded
 * in docs/site-notes.md — keep the two in sync.
 *
 * Conventions: scripts return a short string ("KIND|detail") or a JSON string; evaluateJavascript
 * hands that back JSON-encoded, so decode with [MainActivity.decodeJs]. In Kotlin raw strings a
 * literal dollar sign must be written ${'$'}{'$'}.
 */
object Js {

    private fun q(s: String) = s.replace("\\", "").replace("\"", "").replace("\n", " ")

    /** Shared helpers, prepended to scripts that need them. */
    private const val HELPERS = """
        function __txt(e){ return ((e.innerText||'')+' '+(e.value||'')).replace(/\s+/g,' ').trim(); }
        function __vis(e){ if(!e) return false; var st=getComputedStyle(e);
          if(st.display==='none'||st.visibility==='hidden') return false;
          var r=e.getBoundingClientRect(); return r.width>0 && r.height>0; }
        function __inChrome(e){ return !!(e.closest && e.closest('header,nav,footer,.top-nav,.header,.navbar,.menu')); }
        function __tap(e){ try{ ['mousedown','mouseup'].forEach(function(t){
            e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window})); }); }catch(x){}
          e.click(); }
    """

    /**
     * Dismiss a leftover modal, then find the target BOOK link (by booking-URL substring, else by
     * keyword row) — retrying for up to 1.5s because the table can appear after onPageFinished.
     * Reports via Android.onClicked / Android.onResult('NO_SERVICE').
     */
    fun click(keyword: String, bookingId: String): String = """
        (function(){
          try{
            var conf=document.querySelector('.sweet-alert button.confirm,.swal2-confirm,.sa-button-container button');
            if(!conf){ var bs=document.querySelectorAll('button'); for(var b=0;b<bs.length;b++){ if((bs[b].innerText||'').trim().toLowerCase()==='ok' && bs[b].offsetParent!==null){ conf=bs[b]; break; } } }
            if(conf){ try{conf.click();}catch(e){} }
            var idsub="${q(bookingId)}", kw="${q(keyword)}".toLowerCase(), t0=Date.now();
            function find(){
              if(idsub){ var links=document.querySelectorAll('a,button');
                for(var k=0;k<links.length;k++){ if((links[k].getAttribute('href')||'').indexOf(idsub)>=0) return links[k]; } }
              if(kw){ var rows=document.querySelectorAll('tr');
                for(var i=0;i<rows.length;i++){ if((rows[i].innerText||'').toLowerCase().indexOf(kw)<0) continue;
                  var as=rows[i].querySelectorAll('a,button');
                  for(var j=0;j<as.length;j++){ var et=(as[j].innerText||'').toLowerCase(), h=(as[j].getAttribute('href')||'');
                    if(et.indexOf('book')>=0||et.indexOf('prenot')>=0||h.indexOf('/Services/Booking/')>=0) return as[j]; }
                  var a=rows[i].querySelector('a'); if(a) return a; } }
              return null;
            }
            (function tryIt(){
              var t=find();
              if(t){ Android.onClicked(Date.now()-t0); t.click(); return; }
              if(Date.now()-t0<1500){ setTimeout(tryIt,100); return; }
              Android.onResult('NO_SERVICE');
            })();
          }catch(e){ Android.onResult('ERR:'+e); }
        })();
    """.trimIndent()

    /** Outcome poll after a click: SOLD|snippet, SUCCESS|, ERR5XX|title, BOOK|, WAIT|. */
    val STATE = """
        (function(){
          try{
            var body=((document.body?document.body.innerText:'')+'').toLowerCase();
            var keys=['all appointments for this service','currently unavailable','sold out','high demand',
                      'esaurit','elevata richiesta','tutti gli appuntamenti','al momento non'];
            for(var i=0;i<keys.length;i++){ var k=body.indexOf(keys[i]);
              if(k>=0) return 'SOLD|'+body.substring(Math.max(0,k-80),k+200).replace(/\s+/g,' '); }
            var p=location.pathname.toLowerCase();
            if(p.indexOf('/services/booking/')>=0){
              var t=(document.title||'').toLowerCase();
              if(/\b50[234]\b|bad gateway|service unavailable|gateway time/.test(t+' '+body.substring(0,400))) return 'ERR5XX|'+t;
              if(body.indexOf('send new code')>=0||body.indexOf('invia nuovo codice')>=0||body.indexOf('otp')>=0||
                 document.querySelector('input[name*=otp i],input[id*=otp i]')) return 'SUCCESS|';
              return 'BOOK|';
            }
            return 'WAIT|';
          }catch(e){ return 'WAIT|'+e; }
        })();
    """.trimIndent()

    /** Find (and optionally click) the SEND NEW CODE button. FOUND|text / CLICKED|text / NOBTN| */
    fun requestOtp(click: Boolean): String = """
        (function(){
          try{ $HELPERS
            var els=document.querySelectorAll('a,button,input[type=button],input[type=submit]');
            for(var i=0;i<els.length;i++){
              var t=__txt(els[i]).toLowerCase();
              if((t.indexOf('otp')>=0||t.indexOf('codice')>=0||t.indexOf('code')>=0) &&
                 (t.indexOf('nuov')>=0||t.indexOf('new')>=0||t.indexOf('invia')>=0||t.indexOf('send')>=0||
                  t.indexOf('richie')>=0||t.indexOf('request')>=0||t.indexOf('resend')>=0||t.indexOf('genera')>=0)){
                if(${if (click) "true" else "false"}){ els[i].click(); return 'CLICKED|'+t.substring(0,30); }
                return 'FOUND|'+t.substring(0,30);
              }
            }
            return 'NOBTN|';
          }catch(e){ return 'ERR|'+e; }
        })();
    """.trimIndent()

    fun fillOtp(code: String): String = """
        (function(){
          try{
            var inp=document.querySelector('input[autocomplete=one-time-code],input[name*=otp i],input[id*=otp i]');
            if(!inp){ var ins=document.querySelectorAll('input');
              for(var i=0;i<ins.length;i++){ var t=(ins[i].type||'').toLowerCase(); var ml=ins[i].maxLength;
                if((t==='text'||t==='tel'||t==='number') && ml>=4 && ml<=8){ inp=ins[i]; break; } } }
            if(!inp) return 'NOFIELD';
            inp.focus(); inp.value='${code.filter { it.isDigit() }}';
            inp.dispatchEvent(new Event('input',{bubbles:true}));
            inp.dispatchEvent(new Event('change',{bubbles:true}));
            return 'FILLED|'+(inp.name||inp.id||'');
          }catch(e){ return 'ERR:'+e; }
        })();
    """.trimIndent()

    /** Cloudflare Turnstile token length: -1 = no turnstile input on page. */
    val TURNSTILE = """
        (function(){ try{ var t=document.querySelector('[name=cf-turnstile-response]');
          return t ? ''+(t.value||'').length : '-1'; }catch(e){ return '-2'; } })();
    """.trimIndent()

    /** Tick the privacy checkbox (fallback: every unchecked one) and click FORWARD/AVANTI. */
    val SUBMIT_OTP = """
        (function(){
          try{ $HELPERS
            var cbs=document.querySelectorAll('input[type=checkbox]'), ticked=0;
            for(var i=0;i<cbs.length;i++){ var c=cbs[i];
              var lbl=((c.closest('label')||{}).innerText||'')+' '+((c.parentElement||{}).innerText||'')+' '+(c.id||'')+' '+(c.name||'');
              if(/privacy/i.test(lbl) && !c.checked){ c.click(); ticked++; } }
            if(!ticked){ for(var j=0;j<cbs.length;j++){ if(!cbs[j].checked){ try{cbs[j].click(); ticked++;}catch(e){} } } }
            var els=document.querySelectorAll('a,button,input[type=submit],input[type=button]'), pick=null;
            for(var k=0;k<els.length;k++){ if(__inChrome(els[k])) continue;
              var t=__txt(els[k]).toUpperCase();
              if(t==='FORWARD'||t==='AVANTI'){ pick=els[k]; break; } }
            if(!pick){ for(var m=0;m<els.length;m++){ if(__inChrome(els[m])) continue;
              var u=__txt(els[m]).toLowerCase();
              if(u.indexOf('forward')>=0||u.indexOf('avanti')>=0||u.indexOf('prosegui')>=0){ pick=els[m]; break; } } }
            if(!pick) return 'NOFWD|ticked='+ticked;
            pick.click(); return 'SUBMIT|'+__txt(pick).substring(0,20)+'|ticked='+ticked;
          }catch(e){ return 'ERR|'+e; }
        })();
    """.trimIndent()

    /**
     * Calendar snapshot as JSON: month label, bookable days (td.day without disabled/old/new),
     * every .fascia with its count, a signature for change detection, and the BOOK button
     * candidates (outerHTML) so we learn its markup even if we never click it.
     */
    val CAL_SNAPSHOT = """
        (function(){
          try{ $HELPERS
            var r={url:location.href};
            var sw=document.querySelector('.datepicker-switch,th.picker-switch,.picker-switch,.datepicker .switch');
            r.month=sw?(sw.innerText||'').trim():'';
            var tds=document.querySelectorAll('td.day'); r.n=tds.length;
            var days=[], cls=[];
            for(var i=0;i<tds.length;i++){ var c=tds[i].className||''; var d=(tds[i].innerText||'').trim();
              cls.push(d+':'+c);
              if(!/(^|\s)(disabled|old|new)(\s|${'$'})/.test(c)) days.push({i:i,d:d,active:/(^|\s)active(\s|${'$'})/.test(c)}); }
            r.days=days;
            // in-flight jQuery AJAX (the real calendar loads availability via jQuery)
            r.busy=(window.jQuery && window.jQuery.active) ? window.jQuery.active : 0;
            // every in-month day "bookable" = availability not applied yet
            var inMonth=0; for(var z=0;z<tds.length;z++){ if(!/(^|\s)(old|new)(\s|${'$'})/.test(tds[z].className||'')) inMonth++; }
            r.allOpen = inMonth>0 && days.length===inMonth;
            var fs=document.querySelectorAll('.fascia'), f=[];
            for(var j=0;j<fs.length;j++){ var t=(fs[j].innerText||'').replace(/\s+/g,' ').trim(); var m=t.match(/\((\d+)\)/);
              f.push({i:j,t:t,n:m?+m[1]:-1,na:/notAvailableFascia/.test(fs[j].className),c:fs[j].className}); }
            r.fasce=f;
            r.fsig=f.map(function(x){return x.t+'/'+x.c;}).join(',');
            r.sig=r.month+'|'+cls.join(',')+'|'+r.fsig;
            var b=document.querySelectorAll('button,a,input[type=submit],input[type=button]'), bc=[];
            for(var k=0;k<b.length;k++){ var bt=__txt(b[k]).toUpperCase();
              if((bt==='BOOK'||bt==='PRENOTA'||bt.indexOf('PRENOTA')===0||bt.indexOf('BOOK')===0) && !__inChrome(b[k]))
                bc.push(b[k].outerHTML.substring(0,300)); }
            r.bookBtns=bc;
            return JSON.stringify(r);
          }catch(e){ return JSON.stringify({err:''+e}); }
        })();
    """.trimIndent()

    fun clickDay(index: Int): String = """
        (function(){ try{ $HELPERS var tds=document.querySelectorAll('td.day'); var e=tds[$index];
          if(!e) return 'NODAY'; __tap(e); return 'CLICKED|'+(e.innerText||'').trim()+'|'+e.className; }catch(x){ return 'ERR|'+x; } })();
    """.trimIndent()

    /** Click the .fascia at [index]; report its class before/after so we learn the "selected" class. */
    fun clickFascia(index: Int): String = """
        (function(){ try{ $HELPERS var fs=document.querySelectorAll('.fascia'); var e=fs[$index];
          if(!e) return 'NOFASCIA'; var before=e.className; __tap(e);
          return 'CLICKED|'+(e.innerText||'').replace(/\s+/g,' ').trim()+'|before='+before+'|after='+e.className;
        }catch(x){ return 'ERR|'+x; } })();
    """.trimIndent()

    /**
     * Click the calendar's BOOK/PRENOTA button: exact text, not in the site header/nav/footer,
     * not a navigation link, after the calendar in document order, visible. Picks the last match.
     */
    val CLICK_BOOK = """
        (function(){
          try{ $HELPERS
            var cal=document.querySelector('td.day'); cal=cal?cal.closest('table'):null;
            var b=document.querySelectorAll('button,a,input[type=submit],input[type=button]'), pick=null, seen=[];
            for(var i=0;i<b.length;i++){ var e=b[i]; var t=__txt(e).toUpperCase();
              if(!(t==='BOOK'||t==='PRENOTA'||t==='BOOK NOW'||t==='PRENOTA ORA')) continue;
              seen.push(e.outerHTML.substring(0,200));
              if(__inChrome(e)) continue;
              var h=(e.getAttribute('href')||'');
              if(e.tagName==='A' && h && h!=='#' && h.indexOf('javascript')!==0) continue;
              if(cal && !(cal.compareDocumentPosition(e) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
              if(!__vis(e)) continue;
              pick=e; }
            if(!pick) return 'NOBTN|'+seen.join(' ## ');
            pick.scrollIntoView({block:'center'}); pick.click();
            return 'CLICKED|'+pick.outerHTML.substring(0,300);
          }catch(x){ return 'ERR|'+x; }
        })();
    """.trimIndent()

    /** Next month: bootstrap/eonasdan datepicker "next" header cell, else an exact '>' element. */
    val NEXT_MONTH = """
        (function(){
          try{ $HELPERS
            var e=document.querySelector('.datepicker-days th.next,th.next,.datepicker th.next');
            if(e){ __tap(e); return 'NEXT|th.next'; }
            var els=document.querySelectorAll('th,a,button,span');
            for(var i=0;i<els.length;i++){ var t=(els[i].innerText||'').trim();
              if((t==='>'||t==='›'||t==='»') && !__inChrome(els[i])){ __tap(els[i]); return 'NEXT|'+els[i].tagName+'.'+els[i].className; } }
            return 'NONEXT|';
          }catch(x){ return 'ERR|'+x; }
        })();
    """.trimIndent()

    /** Visible modal/dialog texts as JSON array. */
    val MODALS = """
        (function(){
          try{ $HELPERS
            var ms=document.querySelectorAll('.modal.in,.modal.show,.swal2-popup,.sweet-alert,.ui-dialog,[role=alertdialog],.alert');
            var out=[]; for(var i=0;i<ms.length;i++){ if(!__vis(ms[i])) continue;
              var t=(ms[i].innerText||'').replace(/\s+/g,' ').trim(); if(t) out.push(t.substring(0,400)); }
            return JSON.stringify(out);
          }catch(e){ return '[]'; }
        })();
    """.trimIndent()

    /** In a visible modal, click an affirmative button (never cancel/annulla). */
    val CONFIRM_MODAL = """
        (function(){
          try{ $HELPERS
            var ms=document.querySelectorAll('.modal.in,.modal.show,.swal2-popup,.sweet-alert,.ui-dialog,[role=alertdialog]');
            var ok=['OK','CONFERMA','CONFIRM','SI','SÌ','YES','PROSEGUI','CONTINUA','CONTINUE','AVANTI','FORWARD','PRENOTA','BOOK'];
            for(var i=0;i<ms.length;i++){ if(!__vis(ms[i])) continue;
              var bs=ms[i].querySelectorAll('button,a,input[type=submit],input[type=button]');
              for(var j=0;j<bs.length;j++){ var t=__txt(bs[j]).toUpperCase();
                if(ok.indexOf(t)>=0 && __vis(bs[j])){ bs[j].click(); return 'CLICKED|'+t; } } }
            return 'NONE|';
          }catch(e){ return 'ERR|'+e; }
        })();
    """.trimIndent()

    /** Short page summary for the page-chain log: title + first 300 chars of body text. */
    val PAGE_SUMMARY = """
        (function(){ try{ return (document.title||'')+' :: '+((document.body?document.body.innerText:'')+'').replace(/\s+/g,' ').trim().substring(0,300); }catch(e){ return 'ERR '+e; } })();
    """.trimIndent()

    /** Full HTML snapshot without scripts/styles/svg, sent to Android.onHtml(tag, html). */
    fun snapshotHtml(tag: String): String = """
        (function(){ try{
          var c=document.documentElement.cloneNode(true);
          c.querySelectorAll('script,style,svg,noscript,iframe').forEach(function(e){ e.remove(); });
          Android.onHtml('${q(tag)}', '<!-- '+location.href+' -->\n'+c.outerHTML);
        }catch(e){ Android.onHtml('${q(tag)}_err', ''+e); } })();
    """.trimIndent()

    val BODY_TEXT = """
        (function(){ try{ return ((document.body?document.body.innerText:'')+'').substring(0,6000); }catch(e){ return 'ERR '+e; } })();
    """.trimIndent()

    val ENGLISH = """
        (function(){
          try{
            var lang=(document.documentElement.getAttribute('lang')||'').toLowerCase();
            if(lang.indexOf('en')===0) return 'EN';
            var els=document.querySelectorAll('a,button,span,li,[onclick]');
            for(var i=0;i<els.length;i++){
              var t=(els[i].innerText||'').trim().toUpperCase();
              if(t==='ENG'||t==='EN'||t==='ENGLISH'){ els[i].click(); return 'CLICKED'; }
            }
            return 'NOLINK';
          }catch(e){ return 'ERR'; }
        })();
    """.trimIndent()

    val SERVICES = """
        (function(){
          try{
            var out=[];
            var rows=document.querySelectorAll('tr');
            for(var i=0;i<rows.length;i++){
              var as=rows[i].querySelectorAll('a,button');
              var href='';
              for(var j=0;j<as.length;j++){
                var h=(as[j].getAttribute('href')||'');
                if(h.indexOf('/Services/Booking/')>=0){ href=h; break; }
              }
              if(href){
                var txt=(rows[i].innerText||'').replace(/\s+/g,' ').trim();
                out.push(txt.substring(0,100)+' @@ '+href);
              }
            }
            Android.onServices(JSON.stringify(out));
          }catch(e){ Android.onServices('["ERR: '+e+'"]'); }
        })();
    """.trimIndent()

    /**
     * Document-start script (prenotami origin only). Everywhere: watches for visible modals and
     * reports new text via Android.onModal. On /BookingCalendar only (when [net]): wraps
     * XHR/fetch to log calendar AJAX (URL, status, timing, first 4KB of the response). Kept off
     * other pages so the booking/OTP page (Turnstile, bot manager) is untouched.
     */
    fun documentStart(net: Boolean): String = """
        (function(){
          try{
            if(window.__pmHook) return; window.__pmHook=1;
            var P=location.pathname.toLowerCase();
            function rep(o){ try{ Android.onNet(JSON.stringify(o)); }catch(e){} }
            function skip(u){ u=(''+u).toLowerCase(); return u.indexOf('matomo')>=0||u.indexOf('cloudflare')>=0||u.indexOf('challenges')>=0||u.indexOf('google')>=0; }
            if(${if (net) "true" else "false"} && P.indexOf('/bookingcalendar')===0){
              var XO=XMLHttpRequest.prototype.open, XS=XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open=function(m,u){ this.__pm={m:m,u:''+u}; return XO.apply(this,arguments); };
              XMLHttpRequest.prototype.send=function(b){
                var x=this, t0=Date.now();
                try{ if(x.__pm && !skip(x.__pm.u)){ x.__pm.b=b?(''+b).substring(0,500):'';
                  x.addEventListener('loadend',function(){ var r='';
                    try{ r=(x.responseType===''||x.responseType==='text')?(x.responseText||'').substring(0,4000):'['+x.responseType+']'; }catch(e){}
                    rep({k:'xhr',m:x.__pm.m,u:x.__pm.u,b:x.__pm.b,s:x.status,ms:Date.now()-t0,r:r}); }); } }catch(e){}
                return XS.apply(this,arguments); };
              if(window.fetch){ var F=window.fetch;
                window.fetch=function(i,o){ var u=(i&&i.url)?i.url:''+i, t0=Date.now(); var p=F.apply(this,arguments);
                  try{ if(!skip(u)) p.then(function(res){ try{ res.clone().text().then(function(t){
                    rep({k:'fetch',m:(o&&o.method)||'GET',u:u,s:res.status,ms:Date.now()-t0,r:t.substring(0,4000)}); }); }catch(e){} }); }catch(e){}
                  return p; }; }
            }
            var last='';
            setInterval(function(){ try{
              var ms=document.querySelectorAll('.modal.in,.modal.show,.swal2-popup,.sweet-alert,.ui-dialog,[role=alertdialog]'), out=[];
              for(var i=0;i<ms.length;i++){ var st=getComputedStyle(ms[i]);
                if(st.display==='none'||st.visibility==='hidden'||st.opacity==='0') continue;
                var t=(ms[i].innerText||'').replace(/\s+/g,' ').trim(); if(t) out.push(t.substring(0,400)); }
              var s=out.join(' ## ');
              if(s!==last){ last=s; if(s) Android.onModal(location.pathname+' :: '+s); }
            }catch(e){} }, 250);
          }catch(e){}
        })();
    """.trimIndent()
}
