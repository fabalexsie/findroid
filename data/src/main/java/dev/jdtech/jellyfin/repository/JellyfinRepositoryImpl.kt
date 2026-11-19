package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.MediaGenreDto
import dev.jdtech.jellyfin.models.SimilarMediaDto
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toFindroidCollection
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidItem
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidPerson
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val localFolderMediaResolver: LocalFolderMediaResolver,
    private val offlineRepository: JellyfinRepositoryOfflineImpl,
) : JellyfinRepository {
    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userViewApi.getUserViews(jellyfinApi.userId!!).content.items
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            try {
                val episode = jellyfinApi.libraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidEpisode(this@JellyfinRepositoryImpl, database)!!
                val resolution = localFolderMediaResolver.resolveWithStatus(itemId, episode.name)
                episode.copy(
                    sources = episode.sources + listOfNotNull(
                        resolution.source ?: if (!resolution.scanCompleted) localFolderMediaResolver.unknownSource(itemId, episode.name) else null
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading episode $itemId from the offline cache")
                offlineRepository.getEpisode(itemId)
            }
        }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            try {
                val movie = jellyfinApi.libraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidMovie(this@JellyfinRepositoryImpl, database)
                val resolution = localFolderMediaResolver.resolveWithStatus(itemId, movie.name)
                movie.copy(
                    sources = movie.sources + listOfNotNull(
                        resolution.source ?: if (!resolution.scanCompleted) localFolderMediaResolver.unknownSource(itemId, movie.name) else null
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading movie $itemId from the offline cache")
                offlineRepository.getMovie(itemId)
            }
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidShow(this@JellyfinRepositoryImpl)
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading series $itemId from the offline cache")
                offlineRepository.getShow(itemId)
            }
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidSeason(this@JellyfinRepositoryImpl)
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading season $itemId from the offline cache")
                offlineRepository.getSeason(itemId)
            }
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi.getItems(jellyfinApi.userId!!).content.items.mapNotNull {
                    it.toFindroidCollection(this@JellyfinRepositoryImpl)
                }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; using cached libraries")
                offlineRepository.getLibraries()
            }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                    .content
                    .toFindroidItemWithAvailability()
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading item $itemId from the offline cache")
                offlineRepository.getItem(itemId)
            }
        }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                        sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                        sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                        startIndex = startIndex,
                        limit = limit,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached media items")
                offlineRepository.getItems(parentId, includeTypes, recursive, sortBy, sortOrder, startIndex, limit)
            }
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<FindroidItem>> {
        return Pager(
                config = PagingConfig(pageSize = 10, enablePlaceholders = false),
                pagingSourceFactory = {
                    ItemsPagingSource(this, parentId, includeTypes, recursive, sortBy, sortOrder)
                },
            )
            .flow
    }

    override suspend fun getPerson(personId: UUID): FindroidPerson =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItem(personId, jellyfinApi.userId!!)
                    .content
                    .toFindroidPerson(this@JellyfinRepositoryImpl)
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached person $personId")
                offlineRepository.getPerson(personId)
            }
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        jellyfinApi.userId!!,
                        personIds = personIds,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached titles for person credits")
                offlineRepository.getPersonItems(personIds, includeTypes, recursive)
            }
        }

    override suspend fun getFavoriteItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        jellyfinApi.userId!!,
                        filters = listOf(ItemFilter.IS_FAVORITE),
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                        recursive = true,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; using cached favorites")
                offlineRepository.getFavoriteItems()
            }
        }

    override suspend fun getSearchItems(query: String): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getItems(
                        jellyfinApi.userId!!,
                        searchTerm = query,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; searching cached media")
                offlineRepository.getSearchItems(query)
            }
        }

    override suspend fun getSuggestions(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.suggestionApi
                    .getSuggestions(
                        jellyfinApi.userId!!,
                        limit = 6,
                        type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; using cached suggestions")
                offlineRepository.getSuggestions()
            }
        }

    override suspend fun getGenreNames(): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val serverId = appPreferences.getValue(appPreferences.currentServer)!!
                val cachedGenres = database.getGenres(serverId)
                if (cachedGenres.isNotEmpty()) return@withContext cachedGenres

                // Populate the normalized genre index on the first home-screen visit.
                var startIndex = 0
                while (true) {
                    val page = jellyfinApi.libraryApi.getItems(
                        userId = jellyfinApi.userId!!,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                        startIndex = startIndex,
                        limit = 200,
                        fields = listOf(ItemFields.GENRES),
                    ).content
                    if (page.items.isEmpty()) break
                    page.items.forEach { item ->
                        database.deleteMediaGenres(serverId, item.id)
                        item.genres.orEmpty().distinct().filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let { genres ->
                            database.insertMediaGenres(genres.map { MediaGenreDto(serverId, item.id, it) })
                        }
                    }
                    startIndex += page.items.size
                    if (page.items.size < 200 || startIndex >= page.totalRecordCount) break
                }
                database.getGenres(serverId)
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; using cached genres")
                offlineRepository.getGenreNames()
            }
        }

    override suspend fun getGenreItems(genre: String): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                val serverId = appPreferences.getValue(appPreferences.currentServer)!!
                val ids = database.getMediaIdsForGenre(serverId, genre)
                if (ids.isNotEmpty()) {
                    ids.mapNotNull { id -> runCatching { getItem(id) }.getOrNull() }
                        .filter { it is FindroidMovie || it is FindroidShow }
                } else {
                    jellyfinApi.libraryApi.getItems(
                        userId = jellyfinApi.userId!!,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                        genres = listOf(genre),
                        fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PEOPLE),
                    ).content.items.mapNotNull { it.toFindroidItemWithAvailability() }
                }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached genre $genre")
                offlineRepository.getGenreItems(genre)
            }
        }

    override suspend fun getSimilarItems(itemId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                val userId = jellyfinApi.userId!!
                val serverId = appPreferences.getValue(appPreferences.currentServer)!!
                val items =
                    jellyfinApi.libraryApi
                        .getSimilarItems(
                            itemId = itemId,
                            userId = userId,
                            limit = 20,
                            fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PEOPLE),
                        )
                        .content
                        .items
                database.deleteSimilarMedia(serverId, itemId)
                val recommendations = items.filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }
                database.insertSimilarMedia(
                    recommendations.mapIndexedNotNull { index, item ->
                        if (item.id == itemId) null else SimilarMediaDto(serverId, itemId, item.id, index)
                    }
                )
                recommendations.mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached similar items for $itemId")
                offlineRepository.getSimilarItems(itemId)
            }
        }

    override suspend fun getResumeItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getResumeItems(
                        jellyfinApi.userId!!,
                        limit = 12,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; using cached resume items")
                offlineRepository.getResumeItems()
            }
        }

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi
                    .getLatestMedia(jellyfinApi.userId!!, parentId = parentId, limit = 16)
                    .content
                    .mapNotNull { it.toFindroidItemWithAvailability() }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached latest media for $parentId")
                offlineRepository.getLatestMedia(parentId)
            }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                try {
                    jellyfinApi.showApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items.map {
                        it.toFindroidSeason(this@JellyfinRepositoryImpl)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellyfin unavailable; loading cached seasons for $seriesId")
                    offlineRepository.getSeasons(seriesId, offline = true)
                }
            } else {
                offlineRepository.getSeasons(seriesId, offline = true)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.showApi
                    .getNextUp(
                        jellyfinApi.userId!!,
                        limit = 24,
                        seriesId = seriesId,
                        enableResumable = false,
                    )
                    .content
                    .items
                    .mapNotNull { episode ->
                        episode.toFindroidEpisode(this@JellyfinRepositoryImpl)?.let { item ->
                            item.withLocalFolderAvailability()
                        }
                    }
            } catch (e: Exception) {
                Timber.w(e, "Jellyfin unavailable; loading cached next-up episodes")
                offlineRepository.getNextUp(seriesId)
            }
        }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                try {
                    jellyfinApi.showApi
                        .getEpisodes(
                            seriesId,
                            jellyfinApi.userId!!,
                            seasonId = seasonId,
                            fields = fields,
                            startItemId = startItemId,
                            limit = limit,
                        )
                        .content
                        .items
                        .mapNotNull { episode ->
                            episode.toFindroidEpisode(this@JellyfinRepositoryImpl, database)?.let { item ->
                                item.withLocalFolderAvailability()
                            }
                        }
                } catch (e: Exception) {
                    Timber.w(e, "Jellyfin unavailable; loading cached episodes for season $seasonId")
                    offlineRepository.getEpisodes(seriesId, seasonId, fields, startItemId, limit, offline = true)
                }
            } else {
                offlineRepository.getEpisodes(seriesId, seasonId, fields, startItemId, limit, offline = true)
            }
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            val sources = mutableListOf<FindroidSource>()
            val cachedName =
                runCatching { database.getMovie(itemId).name }.getOrNull()
                    ?: runCatching { database.getEpisode(itemId).name }.getOrNull()
            val itemName =
                cachedName ?: runCatching {
                    jellyfinApi.libraryApi.getItem(itemId, jellyfinApi.userId!!).content.name.orEmpty()
                }.getOrDefault("")
            if (itemName.isNotBlank()) {
                localFolderMediaResolver.resolve(itemId, itemName)?.let {
                    return@withContext listOf(it)
                }
            }
            val remoteSources = try {
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile =
                                DeviceProfile(
                                    name = "Direct play all",
                                    maxStaticBitrate = 1_000_000_000,
                                    maxStreamingBitrate = 1_000_000_000,
                                    codecProfiles = emptyList(),
                                    containerProfiles = emptyList(),
                                    directPlayProfiles = emptyList(),
                                    transcodingProfiles = emptyList(),
                                    subtitleProfiles =
                                        listOf(
                                            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                                            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                                        ),
                                ),
                            maxStreamingBitrate = 1_000_000_000,
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            } catch (e: Exception) {
                Timber.w(e, "Unable to contact Jellyfin for playback sources of $itemId")
                emptyList()
            }
            sources.addAll(remoteSources)
            sources.addAll(database.getSources(itemId).map { it.toFindroidSource(database) })
            sources
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videoApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toFindroidSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentApi.getItemSegments(itemId).content.items.map {
                        it.toFindroidSegment()
                    }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (_: Exception) {}

                return@withContext jellyfinApi.trickPlayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.PLAY_MEDIA_SOURCE,
                    ),
                supportsMediaControl = true,
            )
        }
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        Timber.d("Sending start $itemId")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        Timber.d("Sending stop $itemId")
        withContext(Dispatchers.IO) {
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
            }
            try {
                jellyfinApi.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = itemId, positionTicks = positionTicks, failed = false)
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            try {
                jellyfinApi.sessionApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        positionTicks = positionTicks,
                    )
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userDataApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userDataApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userDataApi.markPlayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userDataApi.markUnplayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.deviceApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidMovie(database, jellyfinApi.userId!!) }
            )
            items.addAll(
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidShow(database, jellyfinApi.userId!!) }
            )
            items
        }

    override fun getUserId(): UUID {
        return jellyfinApi.userId!!
    }

    private suspend fun BaseItemDto.toFindroidItemWithAvailability(): FindroidItem? =
        this.toFindroidItem(this@JellyfinRepositoryImpl, database)?.let { item ->
            when (item) {
                is FindroidMovie -> item.withLocalFolderAvailability()
                is FindroidEpisode -> item.withLocalFolderAvailability()
                else -> item
            }
        }

    private suspend fun FindroidMovie.withLocalFolderAvailability(): FindroidMovie {
        val resolution = localFolderMediaResolver.resolveWithStatus(id, name)
        val marker = resolution.source ?: if (!resolution.scanCompleted) {
            localFolderMediaResolver.unknownSource(id, name)
        } else null
        return copy(sources = sources + listOfNotNull(marker))
    }

    private suspend fun FindroidEpisode.withLocalFolderAvailability(): FindroidEpisode {
        val resolution = localFolderMediaResolver.resolveWithStatus(id, name)
        val marker = resolution.source ?: if (!resolution.scanCompleted) {
            localFolderMediaResolver.unknownSource(id, name)
        } else null
        return copy(sources = sources + listOfNotNull(marker))
    }
}
