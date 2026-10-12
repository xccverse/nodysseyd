package io.github.nodyssey.data.doubles.session

import io.github.nodyssey.data.local.AccountDao
import io.github.nodyssey.data.local.AccountEntity
import io.github.nodyssey.data.local.AccountSessionEntity
import io.github.nodyssey.data.local.ActiveAccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 多账号测试用的内存 DAO。
 *
 * `Upsert` 语义：同 id 覆盖。`deleteAccountAndSession` 级联删会话。
 */
class FakeAccountDao : AccountDao {
    private val accounts = MutableStateFlow<Map<String, AccountEntity>>(emptyMap())
    private val sessions = MutableStateFlow<Map<String, AccountSessionEntity>>(emptyMap())
    private val active = MutableStateFlow<ActiveAccountEntity?>(null)

    override fun observeAccounts(): Flow<List<AccountEntity>> =
        accounts.map { it.values.sortedByDescending { entity -> entity.lastActiveAtMillis } }

    override fun observeSessions(): Flow<List<AccountSessionEntity>> =
        sessions.map { it.values.toList() }

    override suspend fun account(id: String): AccountEntity? = accounts.value[id]

    override suspend fun accountByUid(uid: Long): AccountEntity? =
        accounts.value.values.firstOrNull { it.uid == uid }

    override suspend fun accountCount(): Int = accounts.value.size

    override suspend fun upsertAccount(account: AccountEntity) {
        accounts.update { it + (account.id to account) }
    }

    override suspend fun touchAccount(id: String, nowMillis: Long) {
        accounts.update { map ->
            val existing = map[id] ?: return@update map
            map + (id to existing.copy(lastActiveAtMillis = nowMillis))
        }
    }

    override suspend fun deleteAccount(id: String) {
        accounts.update { it - id }
    }

    override suspend fun upsertSession(session: AccountSessionEntity) {
        sessions.update { it + (session.accountId to session) }
    }

    override suspend fun session(accountId: String): AccountSessionEntity? = sessions.value[accountId]

    override suspend fun deleteSession(accountId: String) {
        sessions.update { it - accountId }
    }

    override suspend fun deleteAccountAndSession(id: String) {
        deleteSession(id)
        deleteAccount(id)
    }

    override fun observeActiveAccount(): Flow<ActiveAccountEntity?> = active

    override suspend fun activeAccount(): ActiveAccountEntity? = active.value

    override suspend fun setActiveAccount(active: ActiveAccountEntity) {
        this.active.value = active
    }
}
