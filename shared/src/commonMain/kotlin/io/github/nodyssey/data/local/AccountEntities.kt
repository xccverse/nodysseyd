package io.github.nodyssey.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 多账号：账号列表。
 *
 * 一个 uid 一行。`id` 是 `"uid-<uid>"` —— 字符串主键，免得和别表的自增 id 撞车，
 * 也让 DAO 的参数一眼看出是账号 id 而不是 uid。
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val uid: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val addedAtMillis: Long,
    val lastActiveAtMillis: Long,
)

/**
 * 多账号：每个账号的会话快照。
 *
 * Cookie 快照是 JSON（[io.github.nodyssey.data.session.AccountRepository] 负责编解码），
 * 存的是 name/value 对 —— 不存 domain/path/expires，恢复时写回当前站点的 sessionUrls 就够了。
 * `encryptedPassword` 是经 [io.github.nodyssey.data.security.SecretCipher] 加密的密码，
 * 空字符串 = 没记住（网页登录收编的、或用户没勾记住）。
 */
@Entity(
    tableName = "account_sessions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class AccountSessionEntity(
    @PrimaryKey val accountId: String,
    val cookieSnapshotJson: String,
    val encryptedPassword: String,
    val updatedAtMillis: Long,
)

/**
 * 多账号：当前账号。
 *
 * 单行表。`accountId` 为 null = 退出了（但账号还留在列表里，随时可切回）。
 * `sessionFingerprint` 是切过去时罐子的指纹 —— 网页登录易主（指纹对不上）时，
 * 旧账号的快照不更新，免得把别人的会话存进旧账号行里。
 */
@Entity(tableName = "active_account")
data class ActiveAccountEntity(
    @PrimaryKey val id: Int = 0,
    val accountId: String?,
    val sessionFingerprint: Int = 0,
)
