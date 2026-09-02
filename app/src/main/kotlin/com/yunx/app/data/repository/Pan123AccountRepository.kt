package com.yunx.app.data.repository

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.yunx.app.data.db.Pan123AccountDao
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.network.Pan123Api
import kotlinx.coroutines.flow.Flow

/**
 * 123 云盘账号仓库：网页登录（yun.123pan.cn 的 localStorage authorToken）→ JWT 落库。
 * 凭证 = authorToken（Bearer JWT，与旧 sign_in 接口返回的 data.token 同源同形，约 90 天过期）；
 * token 失效时重新走网页登录（无 refresh 接口）。
 */
class Pan123AccountRepository(
    private val dao: Pan123AccountDao,
    private val api: Pan123Api
) {

    fun observeAccount(): Flow<Pan123AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan123AccountEntity? = dao.getAccount()

    /**
     * 网页登录凭证（authorToken）校验并落库：先用 user/info 接口确认 token 有效并取昵称，成功返回 true。
     * @param token 123 云盘网页 localStorage 的 authorToken（Bearer JWT）
     */
    suspend fun saveToken(token: String): Boolean {
        val t = token.trim()
        if (t.isBlank()) return false
        // 网页登录拿不到手机号：account 留空，账号页展示时回退昵称
        val nickname = api.fetchNickname(t) ?: return false
        dao.upsert(
            Pan123AccountEntity(
                id = "pan123",
                accessToken = t,
                account = "",
                nickname = nickname
            )
        )
        return true
    }

    /** 校验当前 token 是否仍有效（失败自动清库，下次重新登录） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.accessToken) != null
        if (!ok) dao.clear()
        return ok
    }

    /**
     * 退出登录：清库 + 清理 WebView 登录态（Cookie 与 localStorage/DOM 存储）。
     * 网页登录态保存在 WebView 里；不清理的话，用户再次打开登录页时，页面残留的
     * authorToken 会被自动登录检测直接登回旧账号，导致「退出登录」形同虚设。
     * WebView 存储方法须在带 Looper 的线程（主线程）调用——本方法由 viewModelScope（Main）执行。
     */
    suspend fun logout() {
        // 清 Cookie：顺带清掉 WebView 里其他平台的会话（与百度/夸克等登出行为一致，均为全量清理）
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        // 清 localStorage/DOM 存储：authorToken 就存在这里
        runCatching { WebStorage.getInstance().deleteAllData() }
        dao.clear()
    }
}
