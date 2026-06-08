package com.speaker

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 客户端
 * 支持工具调用（function calling）— 天气、时间、闹钟、计算等
 */
class DeepSeekClient {

    companion object {
        private const val API_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val TIMEOUT_SEC = 60L
        private const val MAX_TOOL_ROUNDS = 5  // 最多工具调用轮数
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private var apiKey: String = ""
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val messages = mutableListOf<JSONObject>()
    private val toolExecutor = ToolExecutor()

    fun setApiKey(key: String) {
        apiKey = key
        toolExecutor.setApiKey(key)
    }

    /**
     * System prompt — 告诉模型它可以做什么
     */
    private fun systemPrompt(): JSONObject {
        return JSONObject().apply {
            put("role", "system")
            put("content", """你是智能音箱的语音助手，请用简洁自然的语言回答，控制在100字以内。
你是通过语音播报的，所以回答要口语化、适合朗读。
你不知道实时信息时，请调用提供的工具获取。
用户可能说方言或口音不标准，尽量理解意图。
如果工具调用失败，友好地告诉用户并提供替代建议。""")
        }
    }

    /**
     * 工具定义 — 描述每个工具给 DeepSeek
     */
    private fun toolDefinitions(): JSONArray {
        return JSONArray().apply {
            // 1. 查天气
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "get_weather")
                    put("description", "获取某个城市的实时天气。城市名可以是中文如'北京'，也可以是英文如'Beijing'")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("city", JSONObject().apply {
                                put("type", "string")
                                put("description", "城市名，如 北京、上海、广州 等")
                            })
                        })
                        put("required", JSONArray().apply { put("city") })
                    })
                })
            })

            // 2. 看时间
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "get_current_time")
                    put("description", "获取当前日期和时间，不需要参数")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                        put("required", JSONArray())
                    })
                })
            })

            // 3. 设置闹钟
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "set_alarm")
                    put("description", "设置一个指定时间的闹钟")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("hour", JSONObject().apply {
                                put("type", "integer")
                                put("description", "小时（0-23）")
                            })
                            put("minute", JSONObject().apply {
                                put("type", "integer")
                                put("description", "分钟（0-59）")
                            })
                            put("label", JSONObject().apply {
                                put("type", "string")
                                put("description", "闹钟标签，如'起床''开会'")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("hour"); put("minute")
                        })
                    })
                })
            })

            // 4. 设置倒计时
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "set_timer")
                    put("description", "设置一个倒计时")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("minutes", JSONObject().apply {
                                put("type", "integer")
                                put("description", "分钟数")
                            })
                            put("label", JSONObject().apply {
                                put("type", "string")
                                put("description", "提醒标签")
                            })
                        })
                        put("required", JSONArray().apply { put("minutes") })
                    })
                })
            })

            // 5. 简单计算
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "calculate")
                    put("description", "执行数学计算，支持 + - * / 和括号")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("expression", JSONObject().apply {
                                put("type", "string")
                                put("description", "数学表达式，如 (23+15)*2")
                            })
                        })
                        put("required", JSONArray().apply { put("expression") })
                    })
                })
            })

            // 6. 搜索新闻
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "get_news")
                    put("description", "获取最新新闻，可选指定类别或关键词")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("category", JSONObject().apply {
                                put("type", "string")
                                put("description", "新闻类别：科技、财经、体育、娱乐、国内、国际等")
                            })
                            put("keyword", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索关键词")
                            })
                        })
                        put("required", JSONArray())
                    })
                })
            })
        }
    }

    /**
     * 发送问题，支持工具调用循环
     * @param text 用户语音输入
     * @param callback (回答文字, 是否有工具被调用) -> Unit
     */
    fun ask(text: String, callback: (String?) -> Unit) {
        if (apiKey.isEmpty()) {
            callback("请先在设置中配置 API Key")
            return
        }

        // 加入用户消息
        messages.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        // 执行工具循环
        executeWithTools(0, callback)
    }

    private fun executeWithTools(round: Int, finalCallback: (String?) -> Unit) {
        if (round >= MAX_TOOL_ROUNDS) {
            // 超出最大轮数，强制让模型总结
            messages.add(JSONObject().apply {
                put("role", "user")
                put("content", "请根据已有的信息给出最终回答")
            })
        }

        val requestBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                // system prompt
                put(systemPrompt())
                // 对话历史
                for (msg in messages) {
                    put(msg)
                }
            })
            put("tools", toolDefinitions())
            put("tool_choice", "auto")
            put("temperature", 0.7)
            put("max_tokens", 2048)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finalCallback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.let { body ->
                    try {
                        val json = JSONObject(body.string())
                        val choice = json.getJSONArray("choices").getJSONObject(0)
                        val message = choice.getJSONObject("message")

                        // 检查是否有工具调用
                        if (message.has("tool_calls")) {
                            // 保存模型的中间思考（可选）
                            if (message.has("content") && !message.isNull("content")) {
                                val content = message.getString("content")
                                if (content.isNotEmpty()) {
                                    messages.add(JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", content)
                                    })
                                }
                            }

                            val toolCalls = message.getJSONArray("tool_calls")
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val funcName = tc.getJSONObject("function").getString("name")
                                val funcArgs = tc.getJSONObject("function").getString("arguments")
                                val toolCallId = tc.getString("id")

                                // 保存 assistant 的 tool_call 消息
                                messages.add(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", null as String?)
                                    put("tool_calls", JSONArray().apply {
                                        put(tc)
                                    })
                                })

                                // 执行工具
                                val result = toolExecutor.execute(funcName, funcArgs)

                                // 保存 tool 结果
                                messages.add(JSONObject().apply {
                                    put("role", "tool")
                                    put("tool_call_id", toolCallId)
                                    put("content", result)
                                })
                            }

                            // 继续下一轮
                            if (round + 1 < MAX_TOOL_ROUNDS) {
                                executeWithTools(round + 1, finalCallback)
                            } else {
                                // 最后一轮，强制要最终回答
                                messages.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", "请用中文总结以上信息给出最终回答")
                                })
                                executeWithTools(round + 1, finalCallback)
                            }
                        } else {
                            // 没有工具调用，直接返回回答
                            val reply = message.optString("content", "")
                            if (reply.isNotEmpty()) {
                                messages.add(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", reply)
                                })
                                // 控制历史长度
                                trimHistory()
                                finalCallback(reply)
                            } else {
                                finalCallback(null)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        finalCallback(null)
                    }
                } ?: finalCallback(null)
            }
        })
    }

    /**
     * 限制对话历史长度（最多保留 5 轮 = 10 条消息 + system prompt）
     */
    private fun trimHistory() {
        while (messages.size > 10) {
            messages.removeAt(0)
        }
    }

    /**
     * 清除对话历史
     */
    fun clearHistory() {
        messages.clear()
    }
}
