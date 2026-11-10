package com.itcast.ds_balance_viewer.util

import android.content.Context
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.net.DeepSeekClient

/** 把网络层错误码统一翻译成本地化文案，供各页面复用，避免三处重复 when */
object ErrorText {

    fun of(context: Context, error: DeepSeekClient.Result.Error): String = when (error.code) {
        DeepSeekClient.ErrorCode.TIMEOUT -> context.getString(R.string.err_timeout)
        DeepSeekClient.ErrorCode.NETWORK -> context.getString(R.string.err_network)
        DeepSeekClient.ErrorCode.UNAUTHORIZED -> context.getString(R.string.err_401)
        DeepSeekClient.ErrorCode.FORBIDDEN -> context.getString(R.string.err_403)
        DeepSeekClient.ErrorCode.RATE_LIMITED -> context.getString(R.string.err_429)
        DeepSeekClient.ErrorCode.SERVER -> context.getString(R.string.err_5xx)
        DeepSeekClient.ErrorCode.PARSE -> context.getString(R.string.err_parse)
        DeepSeekClient.ErrorCode.UNKNOWN ->
            context.getString(R.string.err_unknown, error.detail ?: "")
    }
}
