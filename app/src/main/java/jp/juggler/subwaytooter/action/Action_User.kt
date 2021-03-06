package jp.juggler.subwaytooter.action

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import jp.juggler.subwaytooter.*
import jp.juggler.subwaytooter.api.*
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.dialog.AccountPicker
import jp.juggler.subwaytooter.dialog.ReportForm
import jp.juggler.subwaytooter.table.AcctColor
import jp.juggler.subwaytooter.table.SavedAccount
import jp.juggler.subwaytooter.table.UserRelation
import jp.juggler.subwaytooter.util.TootApiResultCallback
import jp.juggler.util.*
import okhttp3.Request

object Action_User {
	
	// ユーザをミュート/ミュート解除する
	fun mute(
		activity : ActMain,
		access_info : SavedAccount,
		whoArg : TootAccount,
		whoAccessInfo : SavedAccount,
		bMute : Boolean = true,
		bMuteNotification : Boolean = false
	) {
		val whoAcct = whoArg.acct
		
		if(access_info.isMe(whoAcct)) {
			showToast(activity, false, R.string.it_is_you)
			return
		}
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var relationResult : UserRelation? = null
			var whoIdResult : EntityId? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				if(access_info.isPseudo)
					return if(whoAcct.ascii.contains('?')) {
						TootApiResult("can't mute pseudo acct ${whoAcct.pretty}")
					} else {
						val relation = UserRelation.loadPseudo(whoAcct)
						relation.muting = bMute
						relation.savePseudo(whoAcct.ascii)
						relationResult = relation
						TootApiResult()
					}
				
				val whoId = if(whoAccessInfo.host == access_info.host) {
					whoArg.id
				} else {
					val (result, accountRef) = client.syncAccountByAcct(access_info, whoAcct)
					accountRef?.get()?.id ?: return result
				}
				whoIdResult = whoId
				
				return if(access_info.isMisskey) {
					client.request(
						when(bMute) {
							true -> "/api/mute/create"
							else -> "/api/mute/delete"
						},
						access_info.putMisskeyApiToken().apply {
							put("userId", whoId.toString())
						}.toPostRequestBuilder()
					)?.apply {
						if(jsonObject != null) {
							// 204 no content
							
							// update user relation
							val ur = UserRelation.load(access_info.db_id, whoId)
							ur.muting = bMute
							saveUserRelationMisskey(
								access_info,
								whoId,
								TootParser(activity, access_info)
							)
							relationResult = ur
						}
					}
				} else {
					client.request(
						"/api/v1/accounts/${whoId}/${if(bMute) "mute" else "unmute"}",
						when {
							! bMute -> "".toFormRequestBody()
							else ->
								jsonObject {
									put("notifications", bMuteNotification)
								}
									.toRequestBody()
						}.toPost()
					)?.apply {
						val jsonObject = jsonObject
						if(jsonObject != null) {
							relationResult = saveUserRelation(
								access_info,
								parseItem(
									::TootRelationShip,
									TootParser(activity, access_info),
									jsonObject
								)
							)
						}
					}
				}
			}
			
			override fun handleResult(result : TootApiResult?) {
				if(result == null) return  // cancelled.
				
				val relation = relationResult
				val whoId = whoIdResult
				
				if(relation != null && whoId != null) {
					// 未確認だが、自分をミュートしようとするとリクエストは成功するがレスポンス中のmutingはfalseになるはず
					if(bMute && ! relation.muting) {
						showToast(activity, false, R.string.not_muted)
						return
					}
					
					for(column in App1.getAppState(activity).column_list) {
						if(column.access_info.isPseudo) {
							if(relation.muting) {
								// ミュートしたユーザの情報はTLから消える
								column.removeAccountInTimelinePseudo(whoAcct)
							}
							// フォローアイコンの表示更新が走る
							column.updateFollowIcons(access_info)
						} else if(column.access_info == access_info) {
							when {
								! relation.muting -> {
									if(column.type == ColumnType.MUTES) {
										// ミュート解除したら「ミュートしたユーザ」カラムから消える
										column.removeUser(access_info, ColumnType.MUTES, whoId)
									} else {
										// 他のカラムではフォローアイコンの表示更新が走る
										column.updateFollowIcons(access_info)
									}
									
								}
								
								column.type == ColumnType.PROFILE && column.profile_id == whoId -> {
									// 該当ユーザのプロフページのトゥートはミュートしてても見れる
									// しかしフォローアイコンの表示更新は必要
									column.updateFollowIcons(access_info)
								}
								
								else -> {
									// ミュートしたユーザの情報はTLから消える
									column.removeAccountInTimeline(access_info, whoId)
								}
							}
						}
					}
					
					showToast(
						activity,
						false,
						if(relation.muting) R.string.mute_succeeded else R.string.unmute_succeeded
					)
					
				} else {
					showToast(activity, false, result.error)
				}
			}
		})
	}
	
	fun muteConfirm(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		whoAccessInfo : SavedAccount
	) {
		@SuppressLint("InflateParams")
		val view =
			activity.layoutInflater.inflate(R.layout.dlg_confirm, null, false)
		
		val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
		val cbMuteNotification = view.findViewById<CheckBox>(R.id.cbSkipNext)
		
		// Misskey には「このユーザからの通知もミュート」オプションはない
		// 疑似アカウントにもない
		val hasMuteNotification =
			! access_info.isMisskey && ! access_info.isPseudo
		if(hasMuteNotification) {
			tvMessage.text =
				activity.getString(R.string.confirm_mute_user, who.username)
			cbMuteNotification.setText(R.string.confirm_mute_notification_for_user)
			cbMuteNotification.isChecked = true
			// オプション指定つきでミュート
		} else {
			tvMessage.text =
				activity.getString(R.string.confirm_mute_user, who.username)
			cbMuteNotification.visibility = View.GONE
			cbMuteNotification.isChecked = false
		}
		AlertDialog.Builder(activity)
			.setView(view)
			.setNegativeButton(R.string.cancel, null)
			.setPositiveButton(R.string.ok) { _, _ ->
				mute(
					activity,
					access_info,
					who,
					whoAccessInfo,
					bMuteNotification = cbMuteNotification.isChecked
				)
			}
			.show()
	}
	
	fun muteFromAnotherAccount(activity : ActMain, who : TootAccount,whoAccessInfo : SavedAccount) {
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(R.string.account_picker_mute, who.acct.pretty),
			accountListArg = makeAccountListNonPseudo(activity, who.host)
		) { ai ->
			muteConfirm(activity, ai, who,whoAccessInfo)
		}
	}
	
	// ユーザをブロック/ブロック解除する
	fun block(
		activity : ActMain,
		access_info : SavedAccount,
		whoArg : TootAccount,
		whoAccessInfo : SavedAccount,
		bBlock : Boolean
	) {
		val whoAcct = whoArg.acct
		
		if(access_info.isMe(whoAcct)) {
			showToast(activity, false, R.string.it_is_you)
			return
		}
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var relationResult : UserRelation? = null
			var whoIdResult : EntityId? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				if(access_info.isPseudo)
					return if(whoAcct.ascii.contains('?')) {
						TootApiResult("can't block pseudo account ${whoAcct.pretty}")
					} else {
						val relation = UserRelation.loadPseudo(whoAcct)
						relation.blocking = bBlock
						relation.savePseudo(whoAcct.ascii)
						relationResult = relation
						TootApiResult()
					}
				
				val whoId = if(whoAccessInfo.host == access_info.host) {
					whoArg.id
				} else {
					val (result, accountRef) = client.syncAccountByAcct(access_info, whoAcct)
					accountRef?.get()?.id ?: return result
				}
				whoIdResult = whoId
				
				return if(access_info.isMisskey) {
					
					fun saveBlock(v : Boolean) {
						val ur = UserRelation.load(access_info.db_id, whoId)
						ur.blocking = v
						UserRelation.save1Misskey(
							System.currentTimeMillis(),
							access_info.db_id,
							whoId.toString(),
							ur
						)
						relationResult = ur
					}
					
					client.request(
						"/api/blocking/${if(bBlock) "create" else "delete"}",
						access_info.putMisskeyApiToken().apply {
							put("userId", whoId.toString())
						}.toPostRequestBuilder()
					)?.apply {
						val error = this.error
						when {
							// success
							error == null -> saveBlock(bBlock)
							
							// already
							error.contains("already blocking") -> saveBlock(bBlock)
							error.contains("already not blocking") -> saveBlock(bBlock)
							
							// else something error
						}
					}
				} else {
					client.request(
						"/api/v1/accounts/${whoId}/${if(bBlock) "block" else "unblock"}",
						"".toFormRequestBody().toPost()
					)?.apply {
						val jsonObject = this.jsonObject
						if(jsonObject != null) {
							relationResult = saveUserRelation(
								access_info,
								parseItem(
									::TootRelationShip,
									TootParser(activity, access_info),
									jsonObject
								)
							)
						}
					}
				}
			}
			
			override fun handleResult(result : TootApiResult?) {
				
				if(result == null) return  // cancelled.
				
				val relation = relationResult
				val whoId = whoIdResult
				if(relation != null && whoId != null) {
					
					// 自分をブロックしようとすると、blocking==falseで帰ってくる
					if(bBlock && ! relation.blocking) {
						showToast(activity, false, R.string.not_blocked)
						return
					}
					
					for(column in App1.getAppState(activity).column_list) {
						if(column.access_info.isPseudo) {
							if(relation.blocking) {
								// ミュートしたユーザの情報はTLから消える
								column.removeAccountInTimelinePseudo(whoAcct)
							}
							// フォローアイコンの表示更新が走る
							column.updateFollowIcons(access_info)
						} else if(column.access_info == access_info) {
							when {
								! relation.blocking -> {
									if(column.type == ColumnType.BLOCKS) {
										// ブロック解除したら「ブロックしたユーザ」カラムのリストから消える
										column.removeUser(access_info, ColumnType.BLOCKS, whoId)
									} else {
										// 他のカラムではフォローアイコンの更新を行う
										column.updateFollowIcons(access_info)
									}
								}
								
								access_info.isMisskey -> {
									// Misskeyのブロックはフォロー解除とフォロー拒否だけなので
									// カラム中の投稿を消すなどの効果はない
									// しかしカラム中のフォローアイコン表示の更新は必要
									column.updateFollowIcons(access_info)
								}
								
								// 該当ユーザのプロフカラムではブロックしててもトゥートを見れる
								// しかしカラム中のフォローアイコン表示の更新は必要
								column.type == ColumnType.PROFILE && whoId == column.profile_id -> {
									column.updateFollowIcons(access_info)
								}
								
								// MastodonではブロックしたらTLからそのアカウントの投稿が消える
								else -> column.removeAccountInTimeline(access_info, whoId)
							}
						}
					}
					
					showToast(
						activity,
						false,
						if(relation.blocking)
							R.string.block_succeeded
						else
							R.string.unblock_succeeded
					)
				} else {
					showToast(activity, false, result.error)
				}
			}
		})
	}
	
	fun blockConfirm(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		whoAccessInfo : SavedAccount
	) {
		AlertDialog.Builder(activity)
			.setMessage(
				activity.getString(
					R.string.confirm_block_user,
					who.username
				)
			)
			.setNegativeButton(R.string.cancel, null)
			.setPositiveButton(R.string.ok) { _, _ ->
				block(
					activity,
					access_info,
					who,
					whoAccessInfo,
					true
				)
			}
			.show()
	}
	
	fun blockFromAnotherAccount(
		activity : ActMain,
		who : TootAccount,
		whoAccessInfo : SavedAccount
	) {
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(R.string.account_picker_block, who.acct.pretty),
			accountListArg = makeAccountListNonPseudo(activity, who.host)
		) { ai ->
			blockConfirm(activity, ai, who, whoAccessInfo)
		}
	}
	
	//////////////////////////////////////////////////////////////////////////////////////
	
	// URLからユーザを検索してプロフを開く
	private fun profileFromUrlOrAcct(
		activity : ActMain,
		pos : Int,
		access_info : SavedAccount,
		who_url : String,
		acct : Acct
	) {
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var who : TootAccount? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				val (result, ar) = client.syncAccountByUrl(access_info, who_url)
				if(result == null) return null
				who = ar?.get()
				if(who != null) return result
				
				val (r2, ar2) = client.syncAccountByAcct(access_info, acct)
				who = ar2?.get()
				return r2
			}
			
			override fun handleResult(result : TootApiResult?) {
				result ?: return // cancelled.
				
				when(val who = this.who) {
					null -> {
						showToast(activity, true, result.error)
						// 仕方ないのでchrome tab で開く
						App1.openCustomTab(activity, who_url)
					}
					
					else -> activity.addColumn(pos, access_info, ColumnType.PROFILE, who.id)
				}
			}
		})
	}
	
	// アカウントを選んでユーザプロフを開く
	fun profileFromAnotherAccount(
		activity : ActMain,
		pos : Int,
		access_info : SavedAccount,
		who : TootAccount?
	) {
		if(who?.url == null) return
		val who_host = who.host
		
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(
				R.string.account_picker_open_user_who,
				AcctColor.getNickname(access_info, who)
			),
			accountListArg = makeAccountListNonPseudo(activity, who_host)
		) { ai ->
			if(ai.matchHost(access_info.host)) {
				activity.addColumn(pos, ai, ColumnType.PROFILE, who.id)
			} else {
				profileFromUrlOrAcct(activity, pos, ai, who.url, access_info.getFullAcct(who))
			}
		}
	}
	
	// 今のアカウントでユーザプロフを開く
	fun profileLocal(
		activity : ActMain,
		pos : Int,
		access_info : SavedAccount,
		who : TootAccount
	) {
		when {
			access_info.isNA -> profileFromAnotherAccount(activity, pos, access_info, who)
			else -> activity.addColumn(pos, access_info, ColumnType.PROFILE, who.id)
		}
	}
	
	// User URL で指定されたユーザのプロフを開く
	// Intent-Filter や openChromeTabから 呼ばれる
	fun profile(
		activity : ActMain,
		pos : Int,
		access_info : SavedAccount?,
		url : String,
		host : Host,
		user : String,
		original_url : String = url
	) {
		val acct = Acct.parse(user, host)
		
		if(access_info?.isPseudo == false) {
			// 文脈のアカウントがあり、疑似アカウントではない
			
			if(access_info.matchHost(host)) {
				
				// 文脈のアカウントと同じインスタンスなら、アカウントIDを探して開いてしまう
				TootTaskRunner(activity).run(access_info, object : TootTask {
					
					var who : TootAccount? = null
					
					override fun background(client : TootApiClient) : TootApiResult? {
						val (result, ar) = client.syncAccountByAcct(access_info, acct)
						who = ar?.get()
						return result
					}
					
					override fun handleResult(result : TootApiResult?) {
						result ?: return // cancelled
						when(val who = this.who) {
							null -> {
								// ダメならchromeで開く
								App1.openCustomTab(activity, url)
							}
							
							else -> profileLocal(activity, pos, access_info, who)
						}
					}
				})
			} else {
				// 文脈のアカウントと異なるインスタンスなら、別アカウントで開く
				profileFromUrlOrAcct(activity, pos, access_info, url, acct)
			}
			return
		}
		
		// 文脈がない、もしくは疑似アカウントだった
		// 疑似アカウントでは検索APIを使えないため、IDが分からない
		
		if(! SavedAccount.hasRealAccount()) {
			// 疑似アカウントしか登録されていない
			// chrome tab で開く
			App1.openCustomTab(activity, original_url)
		} else {
			AccountPicker.pick(
				activity,
				bAllowPseudo = false,
				bAuto = false,
				message = activity.getString(
					R.string.account_picker_open_user_who,
					AcctColor.getNickname(acct)
				),
				accountListArg = makeAccountListNonPseudo(activity, host),
				extra_callback = { ll, pad_se, pad_tb ->
					
					// chrome tab で開くアクションを追加
					
					val lp = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)
					val b = Button(activity)
					b.setPaddingRelative(pad_se, pad_tb, pad_se, pad_tb)
					b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
					b.isAllCaps = false
					b.layoutParams = lp
					b.minHeight = (0.5f + 32f * activity.density).toInt()
					b.text = activity.getString(R.string.open_in_browser)
					b.setBackgroundResource(R.drawable.btn_bg_transparent_round6dp)
					
					b.setOnClickListener {
						App1.openCustomTab(activity, original_url)
					}
					ll.addView(b, 0)
				}
			) {
				profileFromUrlOrAcct(activity, pos, it, url, acct)
			}
		}
	}
	
	//////////////////////////////////////////////////////////////////////////////////////
	
	// 通報フォームを開く
	fun reportForm(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		status : TootStatus? = null
	) {
		ReportForm.showReportForm(activity, access_info, who, status) { dialog, comment, forward ->
			report(activity, access_info, who, status, comment, forward) {
				dialog.dismissSafe()
			}
		}
	}
	
	// 通報する
	private fun report(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		status : TootStatus?,
		comment : String,
		forward : Boolean,
		onReportComplete : TootApiResultCallback
	) {
		if(access_info.isMe(who)) {
			showToast(activity, false, R.string.it_is_you)
			return
		}
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			override fun background(client : TootApiClient) : TootApiResult? {
				return client.request(
					"/api/v1/reports",
					JsonObject().apply {
						put("account_id", who.id.toString())
						put("comment", comment)
						put("forward", forward)
						if(status != null) {
							put("status_ids", jsonArray {
								add(status.id.toString())
							})
						}
					}.toPostRequestBuilder()
				)
			}
			
			override fun handleResult(result : TootApiResult?) {
				result ?: return // cancelled.
				
				if(result.jsonObject != null) {
					onReportComplete(result)
					
					showToast(activity, false, R.string.report_completed)
				} else {
					showToast(activity, true, result.error)
				}
			}
		})
	}
	
	// show/hide boosts from (following) user
	fun showBoosts(
		activity : ActMain, access_info : SavedAccount, who : TootAccount, bShow : Boolean
	) {
		if(access_info.isMe(who)) {
			showToast(activity, false, R.string.it_is_you)
			return
		}
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var relation : UserRelation? = null
			override fun background(client : TootApiClient) : TootApiResult? {
				
				val result = client.request(
					"/api/v1/accounts/${who.id}/follow",
					jsonObject {
						try {
							put("reblogs", bShow)
						} catch(ex : Throwable) {
							return TootApiResult(ex.withCaption("json encoding error"))
						}
					}.toPostRequestBuilder()
				)
				
				val jsonObject = result?.jsonObject
				if(jsonObject != null) {
					relation =
						saveUserRelation(
							access_info,
							parseItem(
								::TootRelationShip,
								TootParser(activity, access_info),
								jsonObject
							)
						)
				}
				return result
			}
			
			override fun handleResult(result : TootApiResult?) {
				
				if(result == null) return  // cancelled.
				
				if(relation != null) {
					showToast(activity, true, R.string.operation_succeeded)
				} else {
					showToast(activity, true, result.error)
				}
			}
		})
	}
	
	// メンションを含むトゥートを作る
	private fun mention(
		activity : ActMain,
		account : SavedAccount,
		initial_text : String
	) {
		ActPost.open(
			activity,
			ActMain.REQUEST_CODE_POST,
			account.db_id,
			initial_text = initial_text
		)
	}
	
	// メンションを含むトゥートを作る
	fun mention(
		activity : ActMain, account : SavedAccount, who : TootAccount
	) {
		mention(activity, account, "@${account.getFullAcct(who).ascii} ")
	}
	
	// メンションを含むトゥートを作る
	fun mentionFromAnotherAccount(
		activity : ActMain, access_info : SavedAccount, who : TootAccount?
	) {
		if(who == null) return
		val who_host = who.host
		
		val initial_text = "@${access_info.getFullAcct(who).ascii} "
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(R.string.account_picker_toot),
			accountListArg = makeAccountListNonPseudo(activity, who_host)
		) { ai ->
			mention(activity, ai, initial_text)
		}
	}
	
	fun deleteSuggestion(
		activity : ActMain,
		access_info : SavedAccount,
		who : TootAccount,
		bConfirmed : Boolean = false
	) {
		if(! bConfirmed) {
			
			val name = who.decodeDisplayName(activity)
			AlertDialog.Builder(activity)
				.setMessage(name.intoStringResource(activity, R.string.delete_succeeded_confirm))
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.ok) { _, _ ->
					deleteSuggestion(activity, access_info, who, bConfirmed = true)
				}
				.show()
			return
		}
		TootTaskRunner(activity).run(access_info, object : TootTask {
			override fun background(client : TootApiClient) : TootApiResult? {
				return client.request("/api/v1/suggestions/${who.id}", Request.Builder().delete())
			}
			
			override fun handleResult(result : TootApiResult?) {
				// cancelled
				result ?: return
				
				// error
				val error = result.error
				if(error != null) {
					showToast(activity, true, result.error)
					return
				}
				
				showToast(activity, false, R.string.delete_succeeded)
				
				// update suggestion column
				for(column in activity.app_state.column_list) {
					column.removeUser(access_info, ColumnType.FOLLOW_SUGGESTION, who.id)
				}
			}
		})
	}
}
