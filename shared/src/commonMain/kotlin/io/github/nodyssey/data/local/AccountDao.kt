package io.github.nodyssey.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 多账号的 DAO。
 *
 * 账号行用 [Upsert] —— 同 uid 重复登录是更新不是新增。
 */
@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY lastActiveAtMillis DESC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account_sessions")
    fun observeSessions(): Flow<List<AccountSessionEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE uid = :uid")
    suspend fun accountByUid(uid: Long): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Upsert
    suspend fun upsertAccount(account: AccountEntity)

    @Query("UPDATE accounts SET lastActiveAtMillis = :nowMillis WHERE id = :id")
    suspend fun touchAccount(id: String, nowMillis: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    @Upsert
    suspend fun upsertSession(session: AccountSessionEntity)

    @Query("SELECT * FROM account_sessions WHERE accountId = :accountId")
    suspend fun session(accountId: String): AccountSessionEntity?

    @Query("DELETE FROM account_sessions WHERE accountId = :accountId")
    suspend fun deleteSession(accountId: String)

    /** 删账号连带删它的会话快照 —— 外键 CASCADE 是后备，这一行是明确语义。 */
    @Transaction
    suspend fun deleteAccountAndSession(id: String) {
        deleteSession(id)
        deleteAccount(id)
    }

    @Query("SELECT * FROM active_account WHERE id = 0")
    fun observeActiveAccount(): Flow<ActiveAccountEntity?>

    @Query("SELECT * FROM active_account WHERE id = 0")
    suspend fun activeAccount(): ActiveAccountEntity?

    @Upsert
    suspend fun setActiveAccount(active: ActiveAccountEntity)
}
