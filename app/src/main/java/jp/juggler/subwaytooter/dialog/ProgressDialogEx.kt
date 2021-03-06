@file:Suppress("DEPRECATION")

package jp.juggler.subwaytooter.dialog

import android.app.ProgressDialog
import android.content.Context

class ProgressDialogEx(context : Context) : ProgressDialog(context) {
	companion object {
		const val STYLE_SPINNER = ProgressDialog.STYLE_SPINNER
		const val STYLE_HORIZONTAL = ProgressDialog.STYLE_HORIZONTAL
	}
	
	var isIndeterminateEx : Boolean
		get() = isIndeterminate
		set(value) {
			isIndeterminate = value
		}
	
	fun setMessageEx(msg : CharSequence?) =
		setMessage(msg)
	
}
