package io.github.nodyssey.ui.postlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where 「上次看到这里」's anchor falls, decided without a Compose tree.
 *
 * The number of posts left below the line is the whole contract of [lastPositionAnchor]: the bottom
 * of the screen minus two rows, so the two rows the reader was looking at stay under the line.
 */
class LastPositionAnchorTest {

    /** Ten posts, the bottom of the screen is post 5 (index 4): the anchor is 3, leaving 4 and 5 below. */
    @Test
    fun `the anchor sits one row above the bottom of the screen`() {
        assertEquals(3, lastPositionAnchor(lastVisibleIndex = 4, itemCount = 10))
    }

    /** A one-post list has no earlier row to point at, so it anchors on that post. */
    @Test
    fun `a one-post list anchors on that post`() {
        assertEquals(0, lastPositionAnchor(lastVisibleIndex = 0, itemCount = 1))
    }

    /** Two rows is the floor: the anchor cannot move above the first row. */
    @Test
    fun `two visible posts anchor on the first`() {
        assertEquals(0, lastPositionAnchor(lastVisibleIndex = 1, itemCount = 2))
    }

    /** The append spinner is item [itemCount], not a post: the bottom-most post is [itemCount] - 1. */
    @Test
    fun `the append spinner does not become the anchor`() {
        assertEquals(8, lastPositionAnchor(lastVisibleIndex = 10, itemCount = 10))
    }

    /** Nothing loaded is nothing to remember — and nothing to draw. */
    @Test
    fun `an empty list has no anchor`() {
        assertNull(lastPositionAnchor(lastVisibleIndex = null, itemCount = 0))
        assertNull(lastPositionAnchor(lastVisibleIndex = 0, itemCount = 0))
    }
}
