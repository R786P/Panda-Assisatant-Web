package com.r786p.pandaassistant

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.projection.*
import android.os.*
import android.provider.Settings
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.*
import android.view.*
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.Locale

class LiveScreenService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE="result_code"
        const val EXTRA_RESULT_DATA="result_data"
        const val EXTRA_BACKEND_URL="backend_url"
        private const val CH="panda_live_screen"
        private const val ID=7001
        private const val URL="https://panda-assisatant-web.onrender.com/"
    }
    private var wm:WindowManager?=null
    private var bubble:PandaBubble?=null
    private var bubbleLp:WindowManager.LayoutParams?=null
    private var web:WebView?=null
    private var webRoot:FrameLayout?=null
    private var webLp:WindowManager.LayoutParams?=null
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:android.media.ImageReader?=null
    private var thread:HandlerThread?=null
    private var handler:Handler?=null
    private val mainHandler=Handler(Looper.getMainLooper())
    private var recognizer:SpeechRecognizer?=null
    private var tts:TextToSpeech?=null
    @Volatile private var latestFrame=""

    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH,"Panda Live Screen",NotificationManager.IMPORTANCE_LOW))
        tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){tts?.language=Locale("hi","IN");tts?.setSpeechRate(.95f)}}
    }
    override fun onStartCommand(i:Intent?,f:Int,s:Int):Int{
        val code=i?.getIntExtra(EXTRA_RESULT_CODE,0)?:0
        val data=i?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if(code==0||data==null||!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID,notification())
        startProjection(code,data);return START_STICKY
    }
    private fun notification()=Notification.Builder(this,CH).setContentTitle("Panda Assistant").setContentText("Screen access active").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun startProjection(code:Int,data:Intent){
        projection=getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code,data)
        val m=resources.displayMetrics;thread=HandlerThread("panda-screen").also{it.start()};handler=Handler(thread!!.looper)
        reader=android.media.ImageReader.newInstance(m.widthPixels,m.heightPixels,PixelFormat.RGBA_8888,2)
        reader?.setOnImageAvailableListener({r->capture(r.acquireLatestImage())},handler)
        display=projection?.createVirtualDisplay("PandaAssistantLive",m.widthPixels,m.heightPixels,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,handler)
        showBubble()
    }
    private fun capture(im:android.media.Image?){
        if(im==null)return
        try{
            val p=im.planes[0];val stride=p.pixelStride;val row=p.rowStride;val pad=row-stride*im.width;val pw=im.width+pad/stride
            val bmp=Bitmap.createBitmap(pw,im.height,Bitmap.Config.ARGB_8888);bmp.copyPixelsFromBuffer(p.buffer)
            val crop=if(pw!=im.width)Bitmap.createBitmap(bmp,0,0,im.width,im.height)else bmp
            val w=if(crop.width>720)720 else crop.width
            val sc=if(crop.width>w)Bitmap.createScaledBitmap(crop,w,crop.height*w/crop.width,true)else crop
            val out=ByteArrayOutputStream();sc.compress(Bitmap.CompressFormat.JPEG,50,out);latestFrame=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
            if(sc!==crop)sc.recycle();if(crop!==bmp)crop.recycle();bmp.recycle()
        }catch(_:Exception){}finally{im.close()}
    }
    private fun showBubble(){
        wm=getSystemService(WINDOW_SERVICE)as WindowManager
        bubble=PandaBubble(this)
        bubbleLp=WindowManager.LayoutParams(dp(82),dp(82),if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=resources.displayMetrics.widthPixels-dp(98);y=resources.displayMetrics.heightPixels/2}
        bubble?.tap={openWeb()};bubble?.mic={openWeb()};bubble?.close={closeFloating()}
        bubble?.move={dx,dy->val p=bubbleLp!!;p.x=(p.x+dx.toInt()).coerceIn(0,resources.displayMetrics.widthPixels-dp(82));p.y=(p.y+dy.toInt()).coerceIn(0,resources.displayMetrics.heightPixels-dp(82));wm?.updateViewLayout(bubble,p)}
        wm?.addView(bubble,bubbleLp)
    }
    private fun closeFloating(){closeWeb();try{bubble?.let{if(it.parent!=null)wm?.removeView(it)}}catch(_:Exception){ };bubble=null}
    private fun closeWeb(){try{webRoot?.let{if(it.parent!=null)wm?.removeView(it)};web?.stopLoading();web?.destroy()}catch(_:Exception){};web=null;webRoot=null;webLp=null}
    private fun speak(text:String){mainHandler.post{tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"panda_reply")}}
    private fun startHindiVoice(){
        mainHandler.post{
            if(!SpeechRecognizer.isRecognitionAvailable(this)){web?.post{web?.evaluateJavascript("window.pandaVoiceError('Speech recognition unavailable')",null)};return@post}
            recognizer?.destroy()
            recognizer=SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object:RecognitionListener{
                override fun onReadyForSpeech(p:Bundle?){web?.post{web?.evaluateJavascript("window.pandaVoiceReady&&window.pandaVoiceReady()",null)}}
                override fun onBeginningOfSpeech(){}
                override fun onRmsChanged(r:Float){}
                override fun onBufferReceived(b:ByteArray?){}
                override fun onEndOfSpeech(){}
                override fun onError(e:Int){web?.post{web?.evaluateJavascript("window.pandaVoiceError('Voice samajh nahi aayi. Dobara try karo.')",null)}}
                override fun onResults(r:Bundle?){val text=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull();if(!text.isNullOrBlank())web?.post{web?.evaluateJavascript("window.pandaVoiceResult(${org.json.JSONObject.quote(text)})",null)}else web?.post{web?.evaluateJavascript("window.pandaVoiceError('Kuch sunai nahi diya. Dobara try karo.')",null)}}
                override fun onPartialResults(p:Bundle?){ }
                override fun onEvent(t:Int,b:Bundle?){ }
            })
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"hi-IN");putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"hi-IN");putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)}
            recognizer?.startListening(intent)
        }
    }
    private fun openWeb(){
        if(webRoot?.parent!=null)return
        wm=wm?:getSystemService(WINDOW_SERVICE)as WindowManager
        val root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(25,25,28))}
        val content=FrameLayout(this)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.rgb(28,30,39));setPadding(dp(12),0,dp(4),0)}
        val title=TextView(this).apply{text="🐼  Panda Assistant";textSize=16f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setSingleLine(true)}
        header.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
        val min=Button(this).apply{text="−";textSize=22f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setPadding(0,0,0,0);setOnClickListener{minimizeWeb()}}
        header.addView(min,LinearLayout.LayoutParams(dp(48),dp(48)))
        val close=Button(this).apply{text="✕";textSize=18f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setPadding(0,0,0,0);setOnClickListener{closeWeb()}}
        header.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))
        web=WebView(this).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.mediaPlaybackRequiresUserGesture=false;settings.allowFileAccess=true;settings.allowContentAccess=true;setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(object{
                @JavascriptInterface fun screen():String=latestFrame
                @JavascriptInterface fun startVoice(){startHindiVoice()}
                @JavascriptInterface fun speak(text:String){speak(text)}
            },"PandaNative")
            webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,u:String){super.onPageFinished(v,u);v.evaluateJavascript("""
                (function(){const oldFetch=window.fetch;window.fetch=function(url,opt){try{if(String(url).includes('/api/chat')&&opt&&opt.body){const d=JSON.parse(opt.body);const s=window.PandaNative?window.PandaNative.screen():'';if(s){d.image=s;d.mime_type='image/jpeg';opt.body=JSON.stringify(d)}}}catch(e){}return oldFetch(url,opt,opt)}})();
            """.trimIndent(),null)}}
            loadUrl(URL)
        }
        content.addView(web,FrameLayout.LayoutParams(-1,-1));root.addView(content,FrameLayout.LayoutParams(-1,-1).apply{topMargin=dp(48);bottomMargin=dp(24)});root.addView(header,FrameLayout.LayoutParams(-1,dp(48)).apply{gravity=Gravity.TOP})
        val resize=TextView(this).apply{text="↘";textSize=20f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER;setBackgroundColor(Color.rgb(48,50,60));var lastX=0f;var lastY=0f;setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.rawX;lastY=e.rawY;true};MotionEvent.ACTION_MOVE->{val dx=e.rawX-lastX;val dy=e.rawY-lastY;resizeWeb(dx,dy);lastX=e.rawX;lastY=e.rawY;true};else->true}}}
        root.addView(resize,FrameLayout.LayoutParams(dp(42),dp(24)).apply{gravity=Gravity.BOTTOM or Gravity.END})
        var lastX=0f;var lastY=0f;title.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.rawX;lastY=e.rawY;true};MotionEvent.ACTION_MOVE->{moveWeb(e.rawX-lastX,e.rawY-lastY);lastX=e.rawX;lastY=e.rawY;true};else->true}}
        webRoot=root
        val w=(resources.displayMetrics.widthPixels*.78f).toInt().coerceAtLeast(dp(180));val h=(resources.displayMetrics.heightPixels*.62f).toInt().coerceAtLeast(dp(240))
        webLp=WindowManager.LayoutParams(w,h,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(resources.displayMetrics.widthPixels-w)/2;y=(resources.displayMetrics.heightPixels-h)/2;softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE}
        wm?.addView(root,webLp)
    }
    private fun minimizeWeb(){closeWeb()}
    private fun moveWeb(dx:Float,dy:Float){val p=webLp?:return;val dm=resources.displayMetrics;p.x=(p.x+dx.toInt()).coerceIn(0,(dm.widthPixels-p.width).coerceAtLeast(0));p.y=(p.y+dy.toInt()).coerceIn(0,(dm.heightPixels-p.height).coerceAtLeast(0));try{wm?.updateViewLayout(webRoot,p)}catch(_:Exception){}}
    private fun resizeWeb(dx:Float,dy:Float){val p=webLp?:return;val dm=resources.displayMetrics;val minW=dp(180);val minH=dp(240);val maxW=(dm.widthPixels*.96f).toInt();val maxH=(dm.heightPixels*.88f).toInt();p.width=(p.width+dx.toInt()).coerceIn(minW,maxW);p.height=(p.height+dy.toInt()).coerceIn(minH,maxH);p.x=p.x.coerceIn(0,(dm.widthPixels-p.width).coerceAtLeast(0));p.y=p.y.coerceIn(0,(dm.heightPixels-p.height).coerceAtLeast(0));try{wm?.updateViewLayout(webRoot,p)}catch(_:Exception){}}
    override fun onDestroy(){closeWeb();recognizer?.destroy();tts?.stop();tts?.shutdown();try{bubble?.let{if(it.parent!=null)wm?.removeView(it)}}catch(_:Exception){};display?.release();reader?.close();projection?.stop();thread?.quitSafely();super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null
    private class PandaBubble(c:Context):View(c){private val p=Paint(1);var tap:(()->Unit)?=null;var mic:(()->Unit)?=null;var close:(()->Unit)?=null;var move:((Float,Float)->Unit)?=null;var sx=0f;var sy=0f;var drag=false;override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{sx=e.x;sy=e.y;drag=false;return true};MotionEvent.ACTION_MOVE->{val dx=e.x-sx;val dy=e.y-sy;if(dx*dx+dy*dy>100){drag=true;move?.invoke(dx,dy);sx=e.x;sy=e.y};return true};MotionEvent.ACTION_UP->{if(!drag){val w=width.toFloat();val h=height.toFloat();val closeX=w*.86f;val closeY=h*.16f;val cr=w*.13f;val micX=w*.72f;val micY=h*.78f;val mr=w*.20f;if((e.x-closeX)*(e.x-closeX)+(e.y-closeY)*(e.y-closeY)<cr*cr)close?.invoke()else if((e.x-micX)*(e.x-micX)+(e.y-micY)*(e.y-micY)<mr*mr)mic?.invoke()else tap?.invoke()};return true}};return true};override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val x=w/2;val y=h/2;p.color=Color.WHITE;p.setShadowLayer(12f,0f,5f,Color.argb(160,0,0,0));c.drawCircle(x,y,w*.4f,p);p.clearShadowLayer();p.color=Color.DKGRAY;c.drawCircle(x-w*.22f,y-h*.2f,w*.16f,p);c.drawCircle(x+w*.22f,y-h*.2f,w*.16f,p);p.color=Color.WHITE;c.drawOval(x-w*.3f,y-h*.28f,x+w*.3f,y+h*.3f,p);p.color=Color.DKGRAY;c.drawOval(x-w*.17f,y-h*.04f,x-w*.08f,y+h*.08f,p);c.drawOval(x+w*.08f,y-h*.04f,x+w*.17f,y+h*.08f,p);p.color=Color.WHITE;c.drawCircle(x-w*.135f,y-h*.005f,w*.028f,p);c.drawCircle(x+w*.135f,y-h*.005f,w*.028f,p);p.color=Color.rgb(91,91,247);c.drawCircle(w*.72f,h*.78f,w*.19f,p);p.color=Color.rgb(180,55,65);c.drawCircle(w*.86f,h*.16f,w*.13f,p);p.color=Color.WHITE;p.strokeWidth=w*.035f;p.style=Paint.Style.STROKE;c.drawLine(w*.81f,h*.11f,w*.91f,h*.21f,p);c.drawLine(w*.91f,h*.11f,w*.81f,h*.21f,p);p.style=Paint.Style.FILL)}}
