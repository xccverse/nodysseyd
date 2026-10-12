package io.github.nodyssey.data.session

/**
 * The one sign-out. Both screens that offer it call this rather than each keeping its own list of
 * stores to clear — see [DefaultAccountSignOut] for the list and why it lives in one place.
 */
fun interface AccountSignOut {
    suspend fun signOut()
}

/**
 * 多账号之后，退出登录 = 存盘当前会话 → 清场 → 清罐子 → 无 active。
 * 账号本身保留在列表里，随时可切回；彻底删除去账号切换器。
 *
 * 清场清单搬到了 [AccountRepository.evictAccountData] —— 切换和退出是同一件事
 * （"退出"就是"切到无账号"），清单只应存在一份。
 */
class DefaultAccountSignOut(
    private val accounts: AccountRepository,
) : AccountSignOut {
    override suspend fun signOut() {
        accounts.signOutCurrent()
    }
}
