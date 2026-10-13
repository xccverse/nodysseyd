package io.github.nodyssey.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * 首页/版块列表的滚动位置记忆。
 *
 * 记住"退出/刷新那一刻屏幕上最上面那条帖子"的 postId，下次进来在它上面画一条线。
 * 按（版块，排序）分别记 —— 首页和每个版块、每种排序各记一个。
 *
 * `boardSlug` 空字符串 = 首页（Room 主键列不用 null，免得 `IS NULL` 的写法在校验时找麻烦）。
 */
@Entity(
    tableName = "board_scroll_positions",
    primaryKeys = ["boardSlug", "sort"],
)
data class BoardScrollPositionEntity(
    val boardSlug: String,
    val sort: String,
    val postId: Long,
    val updatedAtMillis: Long,
) {
    companion object {
        /** 首页在库里的 boardSlug。 */
        const val HOME_BOARD_SLUG = ""
    }
}

@Dao
interface BoardScrollPositionDao {
    @Upsert
    suspend fun upsert(position: BoardScrollPositionEntity)

    @Query(
        "SELECT * FROM board_scroll_positions WHERE boardSlug = :boardSlug AND sort = :sort",
    )
    suspend fun find(boardSlug: String, sort: String): BoardScrollPositionEntity?

    @Query("DELETE FROM board_scroll_positions WHERE boardSlug = :boardSlug AND sort = :sort")
    suspend fun delete(boardSlug: String, sort: String)

    @Query("DELETE FROM board_scroll_positions")
    suspend fun clearAll()
}
