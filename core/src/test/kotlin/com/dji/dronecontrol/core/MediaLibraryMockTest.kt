package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.port.MediaAssetType
import com.dji.dronecontrol.core.port.MediaListFilter
import com.dji.dronecontrol.core.port.MediaListResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryMockTest {

    private val mock = MockAircraft()

    @Test
    fun refreshList_returnsPhotosAndVideos() = runTest {
        mock.takePhoto(com.dji.dronecontrol.core.model.PhotoParams())
        mock.startRecord()
        mock.stopRecord()

        val all = mock.refreshList(MediaListFilter.ALL) as MediaListResult.Success
        assertTrue(all.entries.any { it.type == MediaAssetType.PHOTO })
        assertTrue(all.entries.any { it.type == MediaAssetType.VIDEO })

        val photos = mock.refreshList(MediaListFilter.PHOTOS) as MediaListResult.Success
        assertEquals(1, photos.entries.size)
    }

    @Test
    fun deleteEntries_removesFromList() = runTest {
        mock.takePhoto(com.dji.dronecontrol.core.model.PhotoParams())
        val before = mock.refreshList(MediaListFilter.ALL) as MediaListResult.Success
        mock.deleteEntries(before.entries)
        val after = mock.refreshList(MediaListFilter.ALL) as MediaListResult.Success
        assertEquals(0, after.entries.size)
    }
}
