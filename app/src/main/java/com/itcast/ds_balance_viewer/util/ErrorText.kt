package com.itcast.ds_balance_viewer.util

import android.content.Context
import com.itcast.ds_balance_viewer.R
import com.itcast.ds_balance_viewer.net.BalanceClient

/**
 * 把网络层错误码统一翻译成本地化文案，供各页面复用，避免三处重复 when。
 *
 * 三家商家的 401 都表示密钥不被接受。Kimi 有个额外坑：国内站
 * （api.moonshot.cn）与国际站（api.moonshot.ai）的 Key 互不通用，
 * 用错站点会直接 401，所以 401 的文案里带上了「或站点不匹配」。
 */
object ErrorText {

    fun of(context: Context, error: BalanceClient.Result.Error): String = when (error.code) {
        BalanceClient.ErrorCode.TIMEOUT -> context.getString(R.string.err_timeout)
        BalanceClient.ErrorCode.NETWORK -> context.getString(R.string.err_network)
        BalanceClient.ErrorCode.UNAUTHORIZED -> context.getString(R.string.err_401)
        BalanceClient.ErrorCode.FORBIDDEN -> context.getString(R.string.err_403)
        BalanceClient.ErrorCode.RATE_LIMITED -> context.getString(R.string.err_429)
        BalanceClient.ErrorCode.SERVER -> context.getString(R.string.err_5xx)
        BalanceClient.ErrorCode.PARSE -> context.getString(R.string.err_parse)
        BalanceClient.ErrorCode.UNKNOWN ->
            context.getString(R.string.err_unknown, error.detail ?: "")
    }
}
