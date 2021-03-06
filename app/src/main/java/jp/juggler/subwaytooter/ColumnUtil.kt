package jp.juggler.subwaytooter

import jp.juggler.subwaytooter.Column.Companion.READ_LIMIT
import jp.juggler.subwaytooter.Column.Companion.log
import jp.juggler.subwaytooter.api.TootApiClient
import jp.juggler.subwaytooter.api.TootApiResult
import jp.juggler.subwaytooter.api.TootParser
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.api.syncAccountByAcct
import jp.juggler.util.*
import java.util.*

internal inline fun <reified T : TimelineItem> addAll(
	dstArg : ArrayList<TimelineItem>?,
	src : List<T>
) : ArrayList<TimelineItem> {
	val dst = dstArg ?: ArrayList(src.size)
	dst.addAll(src)
	return dst
}

internal fun Column.loadListInfo(client : TootApiClient, bForceReload : Boolean) {
	val parser = TootParser(context, access_info)
	if(bForceReload || this.list_info == null) {
		val result = if(isMisskey) {
			client.request(
				"/api/users/lists/show",
				makeMisskeyBaseParameter(parser).apply {
					put("listId", profile_id)
				}.toPostRequestBuilder()
			)
		} else {
			client.request( "/api/v1/lists/${profile_id.toString()}")
		}

		val jsonObject = result?.jsonObject
		if(jsonObject != null) {
			val data = parseItem(::TootList, parser, jsonObject)
			if(data != null) {
				this.list_info = data
				client.publishApiProgress("") // カラムヘッダの再表示
			}
		}
	}
}

internal fun Column.loadAntennaInfo(client : TootApiClient, bForceReload : Boolean) {
	val parser = TootParser(context, access_info)
	if(bForceReload || this.antenna_info == null) {

		val result = if(isMisskey) {
			client.request(
				"/api/antennas/show",
				makeMisskeyBaseParameter(parser).apply {
					put("antennaId", profile_id)
				}.toPostRequestBuilder()
			)
		} else {
			TootApiResult("antenna feature is not supported on Mastodon")
		}

		val jsonObject = result?.jsonObject
		if(jsonObject != null) {
			val data = parseItem(::MisskeyAntenna, parser, jsonObject)
			if(data != null) {
				this.antenna_info = data
				client.publishApiProgress("") // カラムヘッダの再表示
			}
		}
	}
}


internal fun JsonObject.putMisskeyUntil(column : Column, id : EntityId?) : JsonObject {
	when {
		id == null -> {
		}
		column.useDate -> put("untilDate", id.toString().toLong())
		else -> put("untilId", id.toString())
	}
	return this
}

internal fun JsonObject.putMisskeySince(column : Column, id : EntityId?) : JsonObject {
	when {
		id == null -> {
		}
		column.useDate -> put("sinceDate", id.toString().toLong())
		else -> put("sinceId", id.toString())
	}
	return this
}

internal fun JsonObject.addRangeMisskey(column : Column, bBottom : Boolean) : JsonObject {
	if(bBottom) {
		putMisskeyUntil(column, column.idOld)
	} else {
		putMisskeySince(column, column.idRecent)
	}
	
	
	return this
}

internal fun JsonObject.addMisskeyNotificationFilter(column : Column) : JsonObject {
	when(column.quick_filter) {
		Column.QUICK_FILTER_ALL -> {
			val excludeList = jsonArray {
				// Misskeyのお気に入りは通知されない
				// if(dont_show_favourite) ...
				
				if(column.dont_show_boost) {
					add("renote")
					add("quote")
				}
				if(column.dont_show_follow) {
					add("follow")
					add("receiveFollowRequest")
				}
				if(column.dont_show_reply) {
					add("mention")
					add("reply")
				}
				if(column.dont_show_reaction) {
					add("reaction")
				}
				if(column.dont_show_vote) {
					add("poll_vote")
				}
			}
			
			if(excludeList.isNotEmpty()) put("excludeTypes", excludeList)
		}
		
		// QUICK_FILTER_FAVOURITE // misskeyはお気に入りの通知はない
		Column.QUICK_FILTER_BOOST -> put(
			"includeTypes",
			jsonArray("renote", "quote")
		)
		Column.QUICK_FILTER_FOLLOW -> put(
			"includeTypes",
			jsonArray("follow", "receiveFollowRequest")
		)
		Column.QUICK_FILTER_MENTION -> put(
			"includeTypes",
			jsonArray("mention", "reply")
		)
		Column.QUICK_FILTER_REACTION -> put("includeTypes", jp.juggler.util.jsonArray("reaction"))
		Column.QUICK_FILTER_VOTE -> put("includeTypes", jp.juggler.util.jsonArray("poll_vote"))
	}
	
	return this
}

internal fun JsonObject.putMisskeyParamsTimeline(column : Column) : JsonObject {
	if(column.with_attachment && ! column.with_highlight) {
		put("mediaOnly", true)
		put("withMedia", true)
		put("withFiles", true)
		put("media", true)
	}
	return this
}

internal fun Column.makeHashtagAcctUrl(client : TootApiClient) : String? {
	return if(isMisskey) {
		// currently not supported
		null
	} else {
		if(profile_id == null) {
			val (result, whoRef) = client.syncAccountByAcct(access_info, hashtag_acct)
			result ?: return null // cancelled.
			if(whoRef == null) {
				log.w("makeHashtagAcctUrl: ${result.error ?: "?"}")
				return null
			}
			profile_id = whoRef.get().id
		}
		
		val sb = StringBuilder("/api/v1/accounts/${profile_id}/statuses")
			.append("?limit=").append(READ_LIMIT)
			.append("&tagged=").append(hashtag.encodePercent())
		
		if(with_attachment) sb.append("&only_media=true")
		if(instance_local) sb.append("&local=true")
		
		sb.append(makeHashtagExtraQuery())
		
		sb.toString()
	}
}

internal fun Column.makeMisskeyBaseParameter(parser : TootParser?) =
	access_info.putMisskeyApiToken().apply {
		if(access_info.isMisskey) {
			if(parser != null) parser.serviceType = ServiceType.MISSKEY
			put("limit", 40)
		}
	}

internal fun Column.makeMisskeyParamsUserId(parser : TootParser) =
	makeMisskeyBaseParameter(parser).apply {
		put("userId", profile_id.toString())
	}

internal fun Column.makeMisskeyTimelineParameter(parser : TootParser) =
	makeMisskeyBaseParameter(parser).apply {
		putMisskeyParamsTimeline(this@makeMisskeyTimelineParameter)
	}

internal fun Column.makeMisskeyParamsProfileStatuses(parser : TootParser) =
	makeMisskeyParamsUserId(parser).apply {
		putMisskeyParamsTimeline(this@makeMisskeyParamsProfileStatuses)
		if(! dont_show_reply) put("includeReplies", true)
		if(! dont_show_boost) put("includeMyRenotes", true)
	}

internal fun Column.makePublicLocalUrl() : String {
	return when {
		access_info.isMisskey -> "/api/notes/local-timeline"
		with_attachment -> "${Column.PATH_LOCAL}&only_media=true" // mastodon 2.3 or later
		else -> Column.PATH_LOCAL
	}
}

internal fun Column.makeDomainTimelineUrl() : String {
	val base = "/api/v1/timelines/public?limit=$READ_LIMIT&domain=$instance_uri"
	return when {
		access_info.isMisskey -> "/api/notes/local-timeline"
		with_attachment -> "$base&only_media=true"
		else -> base
	}
}

internal fun Column.makeMisskeyHybridTlUrl() : String {
	return when {
		access_info.isMisskey -> "/api/notes/hybrid-timeline"
		with_attachment -> "${Column.PATH_LOCAL}&only_media=true" // mastodon 2.3 or later
		else -> Column.PATH_LOCAL
	}
}

internal fun Column.makePublicFederateUrl() : String {
	
	return if( access_info.isMisskey){
		"/api/notes/global-timeline"
	}else{
		val sb = StringBuilder("/api/v1/timelines/public?limit=$READ_LIMIT")
		if(with_attachment) sb.append("&only_media=true")
		if(remote_only) sb.append("&remote=true")
		sb.toString()
	}
}

internal fun Column.makeHomeTlUrl() : String {
	return when {
		access_info.isMisskey -> "/api/notes/timeline"
		with_attachment -> "${Column.PATH_HOME}&only_media=true"
		else -> Column.PATH_HOME
	}
}

internal fun Column.makeNotificationUrl(
	client : TootApiClient,
	fromAcct : String? = null
) : String {
	return when {
		access_info.isMisskey -> "/api/i/notifications"
		
		else -> {
			val sb = StringBuilder(Column.PATH_NOTIFICATIONS) // always contain "?limit=XX"
			when(val quick_filter = quick_filter) {
				Column.QUICK_FILTER_ALL -> {
					if(dont_show_favourite) sb.append("&exclude_types[]=favourite")
					if(dont_show_boost) sb.append("&exclude_types[]=reblog")
					if(dont_show_follow) sb.append("&exclude_types[]=follow")
					if(dont_show_reply) sb.append("&exclude_types[]=mention")
					if(dont_show_vote) sb.append("&exclude_types[]=poll")
				}
				
				else -> {
					if(quick_filter != Column.QUICK_FILTER_FAVOURITE) sb.append("&exclude_types[]=favourite")
					if(quick_filter != Column.QUICK_FILTER_BOOST) sb.append("&exclude_types[]=reblog")
					if(quick_filter != Column.QUICK_FILTER_FOLLOW) sb.append("&exclude_types[]=follow")
					if(quick_filter != Column.QUICK_FILTER_MENTION) sb.append("&exclude_types[]=mention")
				}
			}
			
			if(fromAcct?.isNotEmpty() == true) {
				if(profile_id == null) {
					val (result, whoRef) = client.syncAccountByAcct(access_info, hashtag_acct)
					if(result != null) {
						whoRef ?: error(result.error ?: "unknown error")
						profile_id = whoRef.get().id
					}
				}
				if(profile_id != null) {
					sb.append("&account_id=").append(profile_id.toString())
				}
			}
			
			// reaction,voteはmastodonにはない
			sb.toString()
		}
	}
}

internal fun Column.makeListTlUrl() : String {
	return if(isMisskey) {
		"/api/notes/user-list-timeline"
	} else {
		"/api/v1/timelines/list/${profile_id}?limit=$READ_LIMIT"
	}
}

internal fun Column.makeAntennaTlUrl() : String {
	return if(isMisskey) {
		"/api/antennas/notes"
	} else {
		"/nonexistent" // Mastodonにはアンテナ機能はない
	}
}

internal fun Column.makeHashtagExtraQuery() : String {
	val sb = StringBuilder()
	hashtag_any.split(" ").filter { it.isNotEmpty() }.forEach {
		sb.append("&any[]=").append(it.encodePercent())
	}
	
	hashtag_all.split(" ").filter { it.isNotEmpty() }.forEach {
		sb.append("&all[]=").append(it.encodePercent())
	}
	
	hashtag_none.split(" ").filter { it.isNotEmpty() }.forEach {
		sb.append("&none[]=").append(it.encodePercent())
	}
	return sb.toString()
}

internal fun Column.makeHashtagUrl() : String {
	return if(isMisskey) {
		"/api/notes/search_by_tag"
	} else {
		// hashtag : String // 先頭の#を含まない
		val sb = StringBuilder("/api/v1/timelines/tag/")
			.append(hashtag.encodePercent())
			.append("?limit=").append(READ_LIMIT)
		
		if(with_attachment) sb.append("&only_media=true")
		if(instance_local) sb.append("&local=true")
		
		sb
			.append(makeHashtagExtraQuery())
			.toString()
	}
}

internal fun Column.makeHashtagParams(parser : TootParser) =
	makeMisskeyTimelineParameter(parser).apply {
		put("tag", hashtag)
		put("limit", Column.MISSKEY_HASHTAG_LIMIT)
	}

// mastodon用
internal fun Column.makeProfileStatusesUrl(profile_id : EntityId?) : String {
	var path = "/api/v1/accounts/$profile_id/statuses?limit=$READ_LIMIT"
	if(with_attachment && ! with_highlight) path += "&only_media=1"
	if(dont_show_boost) path += "&exclude_reblogs=1"
	if(dont_show_reply) path += "&exclude_replies=1"
	return path
}

internal val misskeyArrayFinderUsers = { it : JsonObject ->
	it.jsonArray("users")
}

internal val misskeyFollowingParser =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootAccountRef>()
		jsonArray.objectList().forEach { src ->
			val accountRef = TootAccountRef.mayNull(
				parser,
				parser.account(src.jsonObject("followee"))
			) ?: return@forEach
			
			val relationId = EntityId.mayNull(src.string("id")) ?: return@forEach
			
			accountRef._orderId = relationId
			
			dst.add(accountRef)
		}
		dst
	}

internal val misskeyFollowersParser =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootAccountRef>()
		jsonArray.objectList().forEach { src ->
			val accountRef = TootAccountRef.mayNull(
				parser,
				parser.account(src.jsonObject("follower"))
			) ?: return@forEach
			
			val relationId = EntityId.mayNull(src.string("id")) ?: return@forEach
			
			accountRef._orderId = relationId
			
			dst.add(accountRef)
		}
		dst
	}

internal val misskeyCustomParserFollowRequest =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootAccountRef>()
		jsonArray.objectList().forEach { src ->
			
			val accountRef = TootAccountRef.mayNull(
				parser,
				parser.account(src.jsonObject("follower"))
			) ?: return@forEach
			
			val requestId = EntityId.mayNull(src.string("id")) ?: return@forEach
			
			accountRef._orderId = requestId
			
			dst.add(accountRef)
		}
		
		dst
	}

internal val misskeyCustomParserMutes =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootAccountRef>()
		jsonArray.objectList().forEach { src ->
			
			val accountRef = TootAccountRef.mayNull(
				parser,
				parser.account(src.jsonObject("mutee"))
			) ?: return@forEach
			
			val requestId = EntityId.mayNull(src.string("id")) ?: return@forEach
			
			accountRef._orderId = requestId
			
			dst.add(accountRef)
		}
		
		dst
	}

internal val misskeyCustomParserBlocks =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootAccountRef>()
		jsonArray.objectList().forEach { src ->
			val accountRef = TootAccountRef.mayNull(
				parser,
				parser.account(src.jsonObject("blockee"))
			) ?: return@forEach
			
			val requestId = EntityId.mayNull(src.string("id")) ?: return@forEach
			
			accountRef._orderId = requestId
			
			dst.add(accountRef)
		}
		dst
	}

internal val misskeyCustomParserFavorites =
	{ parser : TootParser, jsonArray : JsonArray ->
		val dst = ArrayList<TootStatus>()
		jsonArray.objectList().forEach { src ->
			val note = parser.status(src.jsonObject("note")) ?: return@forEach
			val favId = EntityId.mayNull(src.string("id")) ?: return@forEach
			note.favourited = true
			note._orderId = favId
			dst.add(note)
		}
		dst
	}

internal val misskeyCustomParserAntenna =
	{ parser : TootParser, array : JsonArray ->
		parser.statusList(array).also { list ->
			list.forEach { it._orderId = it.id }
		}
	}
