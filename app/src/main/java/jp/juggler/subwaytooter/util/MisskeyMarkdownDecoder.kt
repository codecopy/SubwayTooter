package jp.juggler.subwaytooter.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.SparseArray
import android.util.SparseBooleanArray
import jp.juggler.subwaytooter.ActMain
import jp.juggler.subwaytooter.App1
import jp.juggler.subwaytooter.Pref
import jp.juggler.subwaytooter.R
import jp.juggler.subwaytooter.api.entity.EntityIdLong
import jp.juggler.subwaytooter.api.entity.TootMention
import jp.juggler.subwaytooter.span.*
import jp.juggler.subwaytooter.table.HighlightWord
import uk.co.chrisjenx.calligraphy.CalligraphyTypefaceSpan
import java.util.regex.Pattern

// 指定した文字数までの部分文字列を返す
private fun String.safeSubstring(count : Int, offset : Int = 0) = when {
	offset + count <= length -> this.substring(offset, count)
	else -> this.substring(offset, length)
}

// 配列中の要素をラムダ式で変換して、戻り値が非nullならそこで処理を打ち切る
private inline fun <T, V> Array<out T>.firstNonNull(predicate : (T) -> V?) : V? {
	for(element in this) return predicate(element) ?: continue
	return null
}

object MisskeySyntaxHighlighter {
	
	private val keywords = HashSet<String>().apply {
		
		val _keywords = arrayOf(
			"true",
			"false",
			"null",
			"nil",
			"undefined",
			"void",
			"var",
			"const",
			"let",
			"mut",
			"dim",
			"if",
			"then",
			"else",
			"switch",
			"match",
			"case",
			"default",
			"for",
			"each",
			"in",
			"while",
			"loop",
			"continue",
			"break",
			"do",
			"goto",
			"next",
			"end",
			"sub",
			"throw",
			"try",
			"catch",
			"finally",
			"enum",
			"delegate",
			"function",
			"func",
			"fun",
			"fn",
			"return",
			"yield",
			"async",
			"await",
			"require",
			"include",
			"import",
			"imports",
			"export",
			"exports",
			"from",
			"as",
			"using",
			"use",
			"internal",
			"module",
			"namespace",
			"where",
			"select",
			"struct",
			"union",
			"new",
			"delete",
			"this",
			"super",
			"base",
			"class",
			"interface",
			"abstract",
			"static",
			"public",
			"private",
			"protected",
			"virtual",
			"partial",
			"override",
			"extends",
			"implements",
			"constructor"
		)
		
		// lower
		addAll(_keywords)
		
		// UPPER
		addAll(_keywords.map { k -> k.toUpperCase() })
		
		// Snake
		addAll(_keywords.map { k -> k[0].toUpperCase() + k.substring(1) })
		
		add("NaN")
		
		// 識別子に対して既存の名前と一致するか調べるようになったので、もはやソートの必要はない
	}
	
	private val symbolMap = SparseBooleanArray().apply {
		for(c in "=+-*/%~^&|><!?") {
			this.put(c.toInt(), true)
		}
	}
	
	private val stringStart = SparseBooleanArray().apply {
		for(c in "\"'`") {
			this.put(c.toInt(), true)
		}
	}
	
	private class Token(
		val length : Int,
		val color : Int = 0,
		val italic : Boolean = false,
		val comment : Boolean = false
	)
	
	private class Env(val source : String) {
		
		// 出力先
		val sb = SpannableStringBuilder(source)
		
		// 残り部分
		var remain : String = source
			private set
		
		// スキャン位置
		var pos : Int = 0
			set(value) {
				field = value
				remain = source.substring(value)
			}
		
		fun push(start : Int, token : Token) {
			val end = start + token.length
			
			if(token.comment) {
				sb.setSpan(
					ForegroundColorSpan(Color.BLACK or 0x808000)
					, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
				)
			} else {
				var c = token.color
				if(c != 0) {
					if(c < 0x1000000) {
						c = c or Color.BLACK
					}
					sb.setSpan(
						ForegroundColorSpan(c)
						, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
					)
				}
				if(token.italic) {
					sb.setSpan(
						CalligraphyTypefaceSpan(Typeface.defaultFromStyle(Typeface.ITALIC))
						, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
					)
				}
			}
		}
		
		fun parse() : SpannableStringBuilder {
			
			var lastEnd = 0
			fun closeTextToken(textEnd : Int) {
				val length = textEnd - lastEnd
				if(length > 0) {
					push(lastEnd, Token(length = length))
					lastEnd = textEnd
				}
			}
			
			while(remain.isNotEmpty()) {
				val token = elements.firstNonNull { this.it() }
				if(token == null) {
					++ pos
				} else {
					closeTextToken(pos)
					push(pos, token)
					this.pos += token.length
					lastEnd = pos
				}
			}
			closeTextToken(pos)
			
			return sb
		}
	}
	
	private val reLineComment = Pattern.compile("""\A//.*""")
	private val reBlockComment = Pattern.compile("""\A/\*.*?\*/""", Pattern.DOTALL)
	private val reNumber = Pattern.compile("""\A[+-]?[\d.]+""")
	private val reLabel = Pattern.compile("""\A@([A-Z_-][A-Z0-9_-]*)""", Pattern.CASE_INSENSITIVE)
	private val reKeyword =
		Pattern.compile("""\A([A-Z_-][A-Z0-9_-]*)([ \t]*\()?""", Pattern.CASE_INSENSITIVE)
	
	private val elements = arrayOf<Env.() -> Token?>(
		
		// comment
		{
			val match = reLineComment.matcher(remain)
			when {
				! match.find() -> null
				else -> Token(length = match.end(), comment = true)
			}
		},
		
		// block comment
		{
			val match = reBlockComment.matcher(remain)
			when {
				! match.find() -> null
				else -> Token(length = match.end(), comment = true)
			}
		},
		
		// string
		{
			val beginChar = remain[0]
			if(!stringStart[beginChar.toInt()]) return@arrayOf null
			var len = 1
			while(len < remain.length) {
				val char = remain[len ++]
				if(char == beginChar) {
					break // end
				} else if(char == '\n' || len >= remain.length) {
					len = 0 // not string literal
					break
				} else if(char == '\\' && len < remain.length) {
					++ len // \" では閉じないようにする
				}
			}
			when(len) {
				0 -> null
				else -> Token(length = len, color = 0xe96900)
			}
		},
		
		// regexp
		{
			if(remain[0] != '/') return@arrayOf null
			val regexp = StringBuilder()
			var notClosed = false
			var i = 1
			while(i < remain.length) {
				val char = remain[i ++]
				if(char == '/') {
					break
				} else if(char == '\n' || i >= remain.length) {
					notClosed = true
					break
				} else {
					regexp.append(char)
					if(char == '\\' && i < remain.length) {
						regexp.append(remain[i ++])
					}
				}
			}
			when {
				notClosed -> null
				regexp.isEmpty() -> null
				regexp[0] == ' ' && regexp[regexp.length - 1] == ' ' -> null
				else -> Token(length = regexp.length + 2, color = 0xe9003f)
			}
		},
		
		// label
		{
			// 直前に識別子があればNG
			val prev = if(pos <= 0) null else source[pos - 1]
			if(prev?.isLetterOrDigit() == true) return@arrayOf null
			
			val match = reLabel.matcher(remain)
			if(! match.find()) return@arrayOf null
			
			val end = match.end()
			when {
				// @user@host のように直後に@が続くのはNG
				remain.length > end && remain[end] == '@' -> null
				else -> Token(length = match.end(), color = 0xe9003f)
			}
		},
		
		// number
		{
			val prev = if(pos <= 0) null else source[pos - 1]
			if(prev?.isLetterOrDigit() == true) return@arrayOf null
			val match = reNumber.matcher(remain)
			when {
				! match.find() -> null
				else -> Token(length = match.end(), color = 0xae81ff)
			}
		},
		
		// method, property, keyword
		{
			// 直前の文字が識別子に使えるなら識別子の開始とはみなさない
			val prev = if(pos <= 0) null else source[pos - 1]
			if(prev?.isLetterOrDigit() == true || prev == '_') return@arrayOf null
			
			val match = reKeyword.matcher(remain)
			if(! match.find()) return@arrayOf null
			val kw = match.group(1)
			val bracket = match.group(2)
			
			when {
				// メソッド呼び出しは対象が変数かプロパティかに関わらずメソッドの色になる
				bracket?.isNotEmpty() == true ->
					Token(length = kw.length, color = 0x8964c1, italic = true)
				
				// 変数や定数ではなくプロパティならプロパティの色になる
				prev == '.' -> Token(length = kw.length, color = 0xa71d5d)
				
				// 予約語ではない
				// 強調表示しないが、識別子単位で読み飛ばす
				! keywords.contains(kw) -> Token(length = kw.length)
				
				else -> when(kw) {
					
					// 定数
					"true", "false", "null", "nil", "undefined", "NaN" ->
						Token(length = kw.length, color = 0xae81ff)
					
					// その他の予約語
					else -> Token(length = kw.length, color = 0x2973b7)
				}
			}
		},
		
		// symbol
		{
			when {
				symbolMap.get(remain[0].toInt(), false) ->
					Token(length = 1, color = 0x42b983)
				else -> null
			}
		}
	)
	
	fun parse(source : String) = Env(source = source).parse()
	
}

object MisskeyMarkdownDecoder {
	
	private val log = LogCategory("MisskeyMarkdownDecoder")
	
	class SpannableStringBuilderEx : SpannableStringBuilder() {
		var mentions : ArrayList<TootMention>? = null
	}
	
	private class DecodeEnv(val options : DecodeOptions, val sb : SpannableStringBuilderEx) {
		val context : Context
		val font_bold = ActMain.timeline_font_bold
		var start = 0
		var nodeSource : String = ""
		var data : ArrayList<String?>? = null
		val linkHelper : LinkHelper?
		
		init {
			context = options.context ?: error("missing context")
			linkHelper = options.linkHelper
		}
		
		fun urlShorter(display_url : String, href : String) : CharSequence {
			
			if(options.isMediaAttachment(href)) {
				@Suppress("NAME_SHADOWING")
				val sb = SpannableStringBuilder()
				sb.append(href)
				val start = 0
				val end = sb.length
				sb.setSpan(
					EmojiImageSpan(context, R.drawable.emj_1f5bc),
					start,
					end,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
				)
				return sb
			}
			
			try {
				val uri = Uri.parse(display_url)
				
				@Suppress("NAME_SHADOWING")
				val sb = StringBuilder()
				if(! display_url.startsWith("http")) {
					sb.append(uri.scheme)
					sb.append("://")
				}
				sb.append(uri.authority)
				val a = uri.encodedPath
				val q = uri.encodedQuery
				val f = uri.encodedFragment
				val remain = a + (if(q == null) "" else "?$q") + if(f == null) "" else "#$f"
				if(remain.length > 10) {
					sb.append(remain.safeSubstring(10))
					sb.append("…")
				} else {
					sb.append(remain)
				}
				return sb
			} catch(ex : Throwable) {
				log.trace(ex)
				return display_url
			}
		}
		
		fun closePreviousBlock() {
			if(start > 0 && sb[start - 1] != '\n') {
				sb.append('\n')
				start = sb.length
			}
		}
		
		fun setSpan(span : Any) {
			val end = sb.length
			sb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		
		fun setHighlight() {
			val list = options.highlightTrie?.matchList(sb, start, sb.length)
			if(list != null) {
				for(range in list) {
					val word = HighlightWord.load(range.word)
					if(word != null) {
						options.hasHighlight = true
						sb.setSpan(
							HighlightSpan(word.color_fg, word.color_bg),
							range.start,
							range.end,
							Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
						)
						if(word.sound_type != HighlightWord.SOUND_TYPE_NONE) {
							options.highlight_sound = word
						}
					}
				}
			}
		}
		
		fun appendText(text : CharSequence?, preventHighlight : Boolean = false) {
			text ?: return
			
			sb.append(text)
			
			if(! preventHighlight) {
				setHighlight()
			}
		}
		
		fun appendTextCode(text : String?, preventHighlight : Boolean = false) {
			text ?: return
			
			sb.append(MisskeySyntaxHighlighter.parse(text))
			
			if(! preventHighlight) {
				setHighlight()
			}
		}
		
		fun appendLink(text : String, url : String, allowShort : Boolean = false) {
			when {
				text.isEmpty() -> return
				! allowShort -> appendText(text, preventHighlight = true)
				
				else -> {
					val short = urlShorter(text, url)
					appendText(short, preventHighlight = true)
				}
			}
			val linkHelper = options.linkHelper
			if(linkHelper != null) {
				setSpan(
					MyClickableSpan(
						text,
						url,
						linkHelper.findAcctColor(url),
						options.linkTag
					)
				)
			}
			setHighlight()
		}
	}
	
	private class ParseParams(
		val text : String
	) {
		
		var remain : String = ""
		var previous : String = ""
		var pos : Int = 0
			set(value) {
				field = value
				remain = text.substring(pos)
				previous = text.substring(0, pos)
			}
	}
	
	private class Node(
		var start : Int, // ソース文字列中の開始位置
		var length : Int, // ソース文字列中の長さ
		var data : ArrayList<String?>?, // パラメータ
		var decoder : DecodeEnv.() -> Unit // ノード種別
	)
	
	// generate lambda with captured parameter.
	private fun simpleParser(
		pattern : Pattern
		, decoder : DecodeEnv.() -> Unit
	) : (ParseParams) -> Node? = { env : ParseParams ->
		val matcher = pattern.matcher(env.remain)
		when {
			! matcher.find() -> null
			else -> Node(
				env.pos,
				matcher.end(),
				arrayListOf(matcher.group(1)),
				decoder
			)
		}
	}
	
	private val nodeParserMap = SparseArray<Array<out (ParseParams) -> Node?>>().apply {
		
		fun addParser(firstChars : String, vararg nodeParsers : (ParseParams) -> Node?) {
			for(s in firstChars) {
				put(s.toInt(), nodeParsers)
			}
		}
		
		addParser(
			"\""
			, simpleParser(Pattern.compile("""^"([\s\S]+?)\n"""")) {
				closePreviousBlock()
				appendText(trimBlock(data?.get(0)))
				setSpan(BackgroundColorSpan(0x20808080))
				setSpan(CalligraphyTypefaceSpan(Typeface.defaultFromStyle(Typeface.ITALIC)))
				appendText("\n")
			}
		)
		
		addParser(
			":"
			, simpleParser(
				Pattern.compile("""^:([a-zA-Z0-9+-_]+):""")
			) {
				val code = data?.get(0)
				if(code?.isNotEmpty() == true) {
					appendText(options.decodeEmoji(":$code:"))
				}
			}
		)
		
		val dMotion : DecodeEnv.() -> Unit = {
			val code = data?.get(0)
			appendText(code)
			setSpan(MisskeyMotionSpan(ActMain.timeline_font))
		}
		
		addParser(
			"("
			, simpleParser(
				Pattern.compile("""^\Q(((\E(.+?)\Q)))\E""")
				, dMotion
			)
		)
		
		addParser(
			"<"
			, simpleParser(
				Pattern.compile("""^<motion>(.+?)</motion>""")
				, dMotion
			)
		)
		
		addParser(
			"*"
			// 処理順序に意味があるので入れ替えないこと
			// 記号列が長い順
			, simpleParser(
				Pattern.compile("""^\Q***\E(.+?)\Q***\E""")
			) {
				appendText(data?.get(0))
				setSpan(MisskeyBigSpan(font_bold))
			}
			, simpleParser(
				Pattern.compile("""^\Q**\E(.+?)\Q**\E""")
			) {
				appendText(data?.get(0))
				setSpan(CalligraphyTypefaceSpan(font_bold))
			}
		)
		
		addParser(
			"h"
			, simpleParser(
				Pattern.compile("""^(https?://[\w/:%#@${'$'}&?!()\[\]~.=+\-]+)""")
			) {
				val url = data?.get(0)
				if(url?.isNotEmpty() == true) {
					appendLink(url, url, allowShort = true)
				}
			}
		)
		
		// 検索だけはボタン開始位置からバックトラックした方が効率的
		val reSearchButton = Pattern.compile(
			"""^(検索|\[検索]|Search|\[Search])(\n|${'$'})"""
			, Pattern.CASE_INSENSITIVE
		)
		
		fun parseSearchPrev(prev : String) : String? {
			val delm = prev.lastIndexOf('\n')
			val end = prev.length
			return when {
				end <= 1 -> null // キーワードを含まないくらい短い
				delm + 1 >= end - 1 -> null // 改行より後の部分が短すぎる
				! " 　".contains(prev.last()) -> null // 末尾が空白ではない
				else -> prev.substring(delm + 1, end - 1) // キーワード部分を返す
			}
		}
		
		val searchParser = { env : ParseParams ->
			val matcher = reSearchButton.matcher(env.remain)
			when {
				! matcher.find() -> null
				
				else -> {
					val buttonLength = matcher.end()
					val keyword = parseSearchPrev(env.previous)
					when {
						keyword?.isEmpty() != false -> null
						else -> Node(
							env.pos - (keyword.length + 1)
							, buttonLength + (keyword.length + 1)
							, arrayListOf(keyword)
						
						) {
							val text = data?.get(0)
							closePreviousBlock()
							val kw_start = sb.length // キーワードの開始位置
							appendText(text)
							appendText(" ")
							start = sb.length // 検索リンクの開始位置
							appendLink(
								context.getString(R.string.search),
								"https://www.google.co.jp/search?q=" + (text
									?: "Subway Tooter").encodePercent()
							)
							sb.setSpan(
								RelativeSizeSpan(1.2f),
								kw_start,
								sb.length,
								Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
							)
							appendText("\n")
						}
					}
				}
			}
		}
		
		val titleParser = simpleParser(
			Pattern.compile("""^[【\[](.+?)[】\]]\n""")
		) {
			closePreviousBlock()
			appendText(trimBlock(data?.get(0)))
			setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
			setSpan(BackgroundColorSpan(0x20808080))
			setSpan(RelativeSizeSpan(1.5f))
			appendText("\n")
		}
		
		val reLink = Pattern.compile(
			"""^\??\[([^\[\]]+?)]\((https?://[\w/:%#@${'$'}&?!()\[\]~.=+\-]+?)\)"""
		)
		
		val linkParser = { env : ParseParams ->
			val matcher = reLink.matcher(env.remain)
			when {
				! matcher.find() -> null
				else -> Node(
					env.pos
					, matcher.end()
					, arrayListOf(
						matcher.group(1) // title
						, matcher.group(2) // url
						, env.remain[0].toString()   // silent なら "?" になる
					)
				) {
					val title = data?.get(0) ?: "?"
					val url = data?.get(1)
					// val silent = data?.get(2)
					// silentはプレビュー表示を抑制するが、Subwayにはもともとないので関係なかった
					if(url?.isNotEmpty() == true) {
						appendLink(title, url)
					}
				}
			}
		}
		
		addParser("[", titleParser, searchParser, linkParser)
		addParser("【", titleParser)
		addParser("検Ss", searchParser)
		addParser("?", linkParser)
		
		val reMention = Pattern.compile(
			"""^@([a-z0-9_]+)(?:@([a-z0-9.\-]+[a-z0-9]))?"""
			, Pattern.CASE_INSENSITIVE
		)
		
		addParser("@", { env : ParseParams ->
			val matcher = reMention.matcher(env.remain)
			when {
				! matcher.find() -> null
				else -> Node(
					env.pos
					, matcher.end()
					, arrayListOf(matcher.group(1), matcher.group(2)) // username, host
				) {
					
					val username = data?.get(0) ?: ""
					val host = data?.get(1) ?: ""
					
					val linkHelper = linkHelper
					if(linkHelper == null) {
						appendText(
							when {
								host.isEmpty() -> "@$username"
								else -> "@$username@$host"
							}
						)
					} else {
						
						val shortAcct = when {
							host.isEmpty()
								|| host.equals(linkHelper.host, ignoreCase = true) ->
								username
							else ->
								"$username@$host"
						}
						
						val userHost = when {
							host.isEmpty() -> linkHelper.host
							else -> host
						}
						val userUrl = "https://$userHost/@$username"
						
						var mentions = sb.mentions
						if(mentions == null) {
							mentions = ArrayList()
							sb.mentions = mentions
						}
						
						if(mentions.find { it.acct == shortAcct } == null) {
							mentions.add(
								TootMention(
									EntityIdLong(- 1L)
									, userUrl
									, shortAcct
									, username
								)
							)
						}
						
						appendLink(
							when {
								Pref.bpMentionFullAcct(App1.pref) -> "@$username@$userHost"
								else -> "@$shortAcct"
							}
							, userUrl
						)
					}
				}
			}
		})
		
		val reHashtag = Pattern.compile("""^#([^\s]+)""")
		addParser("#"
			, { env : ParseParams ->
				val matcher = reHashtag.matcher(env.remain)
				when {
					! matcher.find() -> null
					else -> when {
						// 先頭以外では直前に空白が必要らしい
						env.pos > 0
							&& ! CharacterGroup.isWhitespace(env.text[env.pos - 1].toInt()) ->
							null
						
						else -> Node(
							env.pos
							, matcher.end()
							, arrayListOf(matcher.group(1)) // 先頭の#を含まない
						
						) {
							val linkHelper = linkHelper
							val tag = data?.get(0)
							if(tag?.isNotEmpty() == true && linkHelper != null) {
								appendLink(
									"#$tag",
									"https://${linkHelper.host}/tags/" + tag.encodePercent()
								)
							}
						}
					}
				}
			}
		)
		
		addParser(
			"`"
			, simpleParser(
				Pattern.compile("""^```(.+?)```""", Pattern.DOTALL)
			
			) {
				closePreviousBlock()
				appendTextCode(trimBlock(data?.get(0)))
				setSpan(BackgroundColorSpan(0x40808080))
				setSpan(RelativeSizeSpan(0.7f))
				setSpan(CalligraphyTypefaceSpan(Typeface.MONOSPACE))
				appendText("\n")
			}
			, simpleParser(
				// インラインコードは内部にとある文字を含むと認識されない。理由は顔文字と衝突するからだとか
				Pattern.compile("""^`([^`´\x0d\x0a]+)`""")
			) {
				appendTextCode(data?.get(0))
				setSpan(BackgroundColorSpan(0x40808080))
				setSpan(CalligraphyTypefaceSpan(Typeface.MONOSPACE))
			}
		)
	}
	
	private val reStartEmptyLines = """\A(?:[ 　]*?[\x0d\x0a]+)+""".toRegex()
	private val reEndEmptyLines = """[\s\x0d\x0a]+\z""".toRegex()
	private fun trimBlock(s : String?) : String? {
		s ?: return null
		return s
			.replace(reStartEmptyLines, "")
			.replace(reEndEmptyLines, "")
	}
	
	private fun parse(source : String?, callback : (Node) -> Unit) {
		if(source != null) {
			val env = ParseParams(source)
			val end = source.length
			var lastEnd = 0 // 直前のノードの終了位置
			var pos = 0 //スキャン中の位置
			
			// 直前のノードの終了位置から次のノードの開始位置の手前までをresultに追加する
			fun closeText(endText : Int) {
				val length = endText - lastEnd
				if(length > 0) callback(
					Node(lastEnd, length, null) {
						appendText(nodeSource)
					}
				)
			}
			
			while(pos < end) {
				val lastParsers = nodeParserMap[source[pos].toInt()]
				if(lastParsers == null) {
					++ pos
					continue
				}
				env.pos = pos
				val node = lastParsers.firstNonNull { it(env) }
				if(node == null) {
					++ pos
					continue
				}
				closeText(node.start)
				callback(node)
				lastEnd = node.start + node.length
				pos = lastEnd
			}
			closeText(pos)
		}
	}
	
	fun decodeMarkdown(options : DecodeOptions, src : String?) =
		SpannableStringBuilderEx().apply {
			try {
				val env = DecodeEnv(options, this)
				
				if(src != null) parse(src) { node ->
					env.nodeSource = src.substring(node.start, node.start + node.length)
					env.start = length
					env.data = node.data
					val decoder = node.decoder
					env.decoder()
				}
				
				// 末尾の空白を取り除く
				val end = length
				var pos = end
				while(pos > 0 && HTMLDecoder.isWhitespaceOrLineFeed(get(pos - 1).toInt())) -- pos
				if(pos < end) delete(pos, end)
				
			} catch(ex : Throwable) {
				log.trace(ex)
				log.e(ex, "decodeMarkdown failed")
			}
		}
}