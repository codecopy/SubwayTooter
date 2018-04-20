package jp.juggler.subwaytooter.span

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.support.annotation.IntRange
import android.support.v4.content.ContextCompat
import android.text.style.ReplacementSpan

import java.lang.ref.WeakReference

class EmojiImageSpan(
	context : Context,
	private val res_id : Int,
	private val useColorShader:Boolean = false
) : ReplacementSpan() {
	
	
	companion object {
		
		// private static final LogCategory warning = new LogCategory( "EmojiImageSpan" );
		
		// static DynamicDrawableSpan x = null;
		
		private const val scale_ratio = 1.14f
		private const val descent_ratio = 0.211f
	}
	
	private val context : Context
	private var mDrawableRef : WeakReference<Drawable>? = null
	
	private val cachedDrawable : Drawable?
		get() {
			var d = mDrawableRef?.get()
			if(d == null) {
				d = ContextCompat.getDrawable(context, res_id) ?: return null
				mDrawableRef = WeakReference(d)
			}
			return d
		}
	
	init {
		this.context = context.applicationContext
	}
	
	private fun getImageSize(paint:Paint) = (0.5f + scale_ratio * paint.textSize).toInt()
	
	override fun getSize(
		paint : Paint,
		text : CharSequence,
		@IntRange(from = 0) start : Int,
		@IntRange(from = 0) end : Int,
		fm : Paint.FontMetricsInt?
	) : Int {
		val size = getImageSize(paint)
		
		if(fm != null) {
			val c_descent = (0.5f + size * descent_ratio).toInt()
			val c_ascent = c_descent - size
			if(fm.ascent > c_ascent) fm.ascent = c_ascent
			if(fm.top > c_ascent) fm.top = c_ascent
			if(fm.descent < c_descent) fm.descent = c_descent
			if(fm.bottom < c_descent) fm.bottom = c_descent
		}
		return size
	}
	
	private var lastColor :Int? = null
	private var lastColorFilter: PorterDuffColorFilter? = null
	
	override fun draw(
		canvas : Canvas,
		text : CharSequence,
		start : Int,
		end : Int,
		x : Float,
		top : Int,
		baseline : Int,
		bottom : Int,
		paint : Paint
	) {
		val d = cachedDrawable ?: return

		val size = getImageSize(paint)
		val c_descent = (0.5f + size * descent_ratio).toInt()
		val transY = baseline - size + c_descent
		
		canvas.save()
		canvas.translate(x, transY.toFloat())
		d.setBounds(0, 0, size, size)
		
		if( useColorShader && d is BitmapDrawable){
			if( paint.color != lastColor || lastColorFilter == null) {
				lastColor = paint.color
				lastColorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_ATOP)
			}
			val saveColorFilter = d.colorFilter
			d.colorFilter = lastColorFilter
			d.draw(canvas)
			d.colorFilter = saveColorFilter
		}else {
			d.draw(canvas)
		}
		canvas.restore()
	}
	
}