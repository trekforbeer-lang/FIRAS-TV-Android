package com.firas.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private data class TvCountry(val name:String,val code:String,val flag:String,val arab:Boolean)

class CountriesActivity:AppCompatActivity(){
 private lateinit var body:FrameLayout
 private lateinit var heading:TextView
 private lateinit var channelAdapter:ChannelAdapter
 private var rows:List<TvCountry> = emptyList()
 private var tab="arab"
 private var channelsOpen=false
 private var generation=0
 private val arabic=mapOf(
  "DZ" to "الجزائر","BH" to "البحرين","KM" to "جزر القمر","DJ" to "جيبوتي","EG" to "مصر","IQ" to "العراق","JO" to "الأردن","KW" to "الكويت","LB" to "لبنان","LY" to "ليبيا","MR" to "موريتانيا","MA" to "المغرب","OM" to "عُمان","PS" to "فلسطين","QA" to "قطر","SA" to "السعودية","SO" to "الصومال","SD" to "السودان","SY" to "سوريا","TN" to "تونس","AE" to "الإمارات","YE" to "اليمن")
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=Color.rgb(9,13,22);window.navigationBarColor=Color.rgb(9,13,22)
  val root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.rgb(9,13,22));layoutDirection=View.LAYOUT_DIRECTION_RTL}
  val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
  bar.addView(Button(this).apply{text="‹";textSize=25f;setTextColor(-1);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(channelsOpen)showCountries() else finish()}},LinearLayout.LayoutParams(dp(48),dp(48)))
  heading=TextView(this).apply{text="قنوات حسب الدولة";textSize=19f;setTextColor(-1)};bar.addView(heading)
  root.addView(bar);body=FrameLayout(this);root.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
  rows=assets.open("countries.csv").bufferedReader(Charsets.UTF_8).useLines{it.drop(1).mapNotNull{line->val p=line.split(",",limit=4);if(p.size<4)null else{val c=p[1].uppercase(Locale.ROOT);TvCountry(arabic[c]?:p[0],c.lowercase(Locale.ROOT),p[3],arabic.containsKey(c))}}.toList().sortedBy{it.name}}
  showCountries()
 }
 private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
 private fun showCountries(){generation++;channelsOpen=false;heading.text="قنوات حسب الدولة";body.removeAllViews()
  val root=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(8),dp(12),dp(8))}
  val tabs=LinearLayout(this).apply{gravity=Gravity.CENTER}
  fun tabButton(label:String,key:String)=Button(this).apply{text=label;isAllCaps=false;setTextColor(-1);background=GradientDrawable().apply{cornerRadius=dp(12).toFloat();setColor(if(tab==key)Color.rgb(177,45,81) else Color.rgb(37,45,61))};setOnClickListener{tab=key;showCountries()}}
  tabs.addView(tabButton("الدول العربية","arab"),LinearLayout.LayoutParams(0,dp(46),1f).apply{marginEnd=dp(5)})
  tabs.addView(tabButton("الدول الأجنبية","foreign"),LinearLayout.LayoutParams(0,dp(46),1f).apply{marginStart=dp(5)})
  root.addView(tabs)
  val scroll=ScrollView(this);val grid=GridLayout(this).apply{columnCount=2}
  rows.filter{it.arab==(tab=="arab")}.forEach{c->val b=Button(this).apply{text=c.flag+"  "+c.name;isAllCaps=false;textSize=13f;setTextColor(-1);setOnClickListener{loadCountry(c)}};grid.addView(b,GridLayout.LayoutParams().apply{width=0;height=dp(58);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(dp(3),dp(3),dp(3),dp(3))})}
  scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));body.addView(root)
 }
 private fun loadCountry(c:TvCountry){channelsOpen=true;val id=++generation;heading.text=c.flag+"  "+c.name;body.removeAllViews()
  val wait=TextView(this).apply{text="جارٍ تحميل قنوات "+c.name+"...";gravity=Gravity.CENTER;setTextColor(-1)};body.addView(wait,FrameLayout.LayoutParams(-1,-1));val spin=ProgressBar(this);body.addView(spin,FrameLayout.LayoutParams(dp(42),dp(42),Gravity.CENTER))
  Thread{val cache=File(cacheDir,"country-"+c.code+".m3u");val list=try{if(!cache.exists()||System.currentTimeMillis()-cache.lastModified()>43200000L)download(c,cache);parse(cache)}catch(e:Exception){if(cache.exists())try{parse(cache)}catch(_:Exception){emptyList()}else emptyList()}
   runOnUiThread{if(id!=generation||isFinishing||isDestroyed)return@runOnUiThread;body.removeAllViews();if(list.isEmpty()){body.addView(TextView(this).apply{text="لا توجد قنوات متاحة أو تعذر الاتصال. حاول لاحقاً.";gravity=Gravity.CENTER;setTextColor(-1)},FrameLayout.LayoutParams(-1,-1))}else{
    channelAdapter=ChannelAdapter(onClick={ch->startActivity(android.content.Intent(this,PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_NAME,ch.name).putExtra(PlayerActivity.EXTRA_URL,ch.url).putExtra(PlayerActivity.EXTRA_REFERER,ch.referer).putExtra(PlayerActivity.EXTRA_CHANNEL_ID,ch.id).putExtra(PlayerActivity.EXTRA_LOGO,ch.logo))});channelAdapter.submitList(list)
    val wrap=LinearLayout(this).apply{orientation=1};wrap.addView(TextView(this).apply{text=list.size.toString()+" قناة";setTextColor(-1);setPadding(dp(16),dp(8),dp(16),dp(8))});wrap.addView(RecyclerView(this).apply{layoutManager=GridLayoutManager(this@CountriesActivity,2);adapter=channelAdapter;setHasFixedSize(false)},LinearLayout.LayoutParams(-1,0,1f));body.addView(wrap)
   }}
  }.start()
 }
 private fun download(c:TvCountry,file:File){val con=URL("https://iptv-org.github.io/iptv/countries/"+c.code+".m3u").openConnection() as HttpURLConnection;con.connectTimeout=8000;con.readTimeout=12000;con.setRequestProperty("User-Agent","FIRAS-TV/4.1");try{if(con.responseCode !in 200..299)throw java.io.IOException("Playlist unavailable");val tmp=File(file.parentFile,file.name+".tmp");var size=0L;con.inputStream.use{input->tmp.outputStream().use{out->val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;size+=n;if(size>6L*1024*1024)throw java.io.IOException("Playlist too large");out.write(buf,0,n)}}};if(!tmp.renameTo(file)){tmp.copyTo(file,true);tmp.delete()}}finally{con.disconnect()}}
 private fun parse(file:File):List<Channel>{val out=ArrayList<Channel>();var name="";var logo="";file.bufferedReader(Charsets.UTF_8).use{r->while(true){val v=(r.readLine()?:break).trim();if(v.startsWith("#EXTINF",true)){name=v.substringAfterLast(',').trim();logo=Regex("""tvg-logo="([^"]*)"""").find(v)?.groupValues?.getOrNull(1).orEmpty()}else if((v.startsWith("http://",true)||v.startsWith("https://",true))&&name.isNotBlank()){out.add(Channel(name,logo,v,"","country-"+v.hashCode()));name="";logo="";if(out.size>=1200)break}}};return out}
 override fun onDestroy(){if(::channelAdapter.isInitialized)channelAdapter.shutdown();super.onDestroy()}
}
