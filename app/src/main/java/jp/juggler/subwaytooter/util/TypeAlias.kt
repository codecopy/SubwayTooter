package jp.juggler.subwaytooter.util

import android.content.DialogInterface
import jp.juggler.subwaytooter.api.TootApiResult
import jp.juggler.subwaytooter.table.SavedAccount

/////////////////////////////////////////////////////////////////
// callback (that returns Unit)

typealias EmptyCallback = ()->Unit

typealias TootApiResultCallback = (result : TootApiResult) -> Unit


typealias SavedAccountCallback = (ai : SavedAccount) -> Unit

typealias DialogInterfaceCallback = (dialog: DialogInterface) -> Unit


typealias ProgressResponseBodyCallback = (bytesRead : Long, bytesTotal : Long)->Unit
