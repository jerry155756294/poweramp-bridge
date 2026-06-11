package com.jerry155756294.powerampbridge.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class IncomingMessage(
  val context: String,
  val data: Any?
)

class JsonMessageCodec {
  fun parse(line: String): IncomingMessage {
    val json = JSONObject(line)
    return IncomingMessage(
      context = json.optString("context"),
      data = unwrap(json.opt("data"))
    )
  }

  fun encode(context: String, data: Any? = ""): String {
    val json = JSONObject()
    json.put("context", context)
    json.put("data", wrap(data))
    return json.toString()
  }

  private fun wrap(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is JSONObject -> value
    is JSONArray -> value
    is Map<*, *> -> JSONObject().apply {
      value.forEach { (key, entry) ->
        put(key.toString(), wrap(entry))
      }
    }
    is Iterable<*> -> JSONArray().apply {
      value.forEach { put(wrap(it)) }
    }
    is Array<*> -> JSONArray().apply {
      value.forEach { put(wrap(it)) }
    }
    is Number, is Boolean, is String -> value
    else -> {
      val parsed = JSONTokener(value.toString()).nextValue()
      if (parsed is JSONObject || parsed is JSONArray) parsed else value.toString()
    }
  }

  private fun unwrap(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> buildMap {
      value.keys().forEach { key ->
        put(key, unwrap(value.opt(key)))
      }
    }
    is JSONArray -> buildList {
      repeat(value.length()) { index ->
        add(unwrap(value.opt(index)))
      }
    }
    else -> value
  }
}
