package dev.jdtech.jellyfin.repository

import android.content.Context
import android.net.Uri
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidItemPersonImage
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration
import org.jellyfin.sdk.model.api.PersonKind

class JellyfinRepositoryOfflineImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val localFolderMediaResolver: LocalFolderMediaResolver,
) : JellyfinRepository {

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        throw Exception("System info not available in offline mode")
    }

    override suspend fun getUserViews(): List<BaseItemDto> {
        return emptyList()
    }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            val movie = database.getMovie(itemId).toFindroidMovie(database, jellyfinApi.userId!!)
            val resolution = localFolderMediaResolver.resolveWithStatus(itemId, movie.name)
            movie.copy(
                sources = movie.sources + listOfNotNull(resolution.source ?: if (!resolution.scanCompleted) localFolderMediaResolver.unknownSource(itemId, movie.name) else null),
                genres = database.getGenresForItem(currentServerId(), itemId),
                people = getCachedPeople(itemId),
                images = cachedImages(itemId),
            )
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            database.getShow(itemId).toFindroidShow(database, jellyfinApi.userId!!).copy(
                genres = database.getGenresForItem(currentServerId(), itemId),
                people = getCachedPeople(itemId),
                images = cachedImages(itemId),
            )
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            database.getSeason(itemId).toFindroidSeason(database, jellyfinApi.userId!!).let { season ->
                season.copy(images = cachedImages(itemId, season.seriesId))
            }
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            val episode = database.getEpisode(itemId).toFindroidEpisode(database, jellyfinApi.userId!!)
            val resolution = localFolderMediaResolver.resolveWithStatus(itemId, episode.name)
            episode.copy(
                sources = episode.sources + listOfNotNull(resolution.source ?: if (!resolution.scanCompleted) localFolderMediaResolver.unknownSource(itemId, episode.name) else null),
                people = getCachedPeople(itemId),
                images = cachedImages(itemId, episode.seriesId),
            )
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        withContext(Dispatchers.IO) {
            database.getCachedLibraries(currentServerId()).map { library ->
                val image = File(context.filesDir, "images/${library.id}/primary")
                FindroidCollection(
                    id = library.id,
                    name = library.name,
                    type = CollectionType.fromString(library.collectionType),
                    images = FindroidImages(primary = image.takeIf { it.exists() }?.let { Uri.fromFile(it) }),
                )
            }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) {
            runCatching { getMovie(itemId) }.getOrNull()
                ?: runCatching { getShow(itemId) }.getOrNull()
                ?: runCatching { getSeason(itemId) }.getOrNull()
                ?: runCatching { getEpisode(itemId) }.getOrNull()
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
            val serverId = currentServerId()
            val allowed = includeTypes?.toSet()
            val allMovies = database.getAllMovies(serverId)
            val allShows = database.getAllShows(serverId)
            val allEpisodes = database.getAllEpisodes(serverId)
            val allSeasons = database.getAllSeasons().filter { it.seriesId in database.getShowsByServerId(serverId).map { show -> show.id }.toSet() }
            val libraryIds =
                if (parentId != null && database.getCachedLibraries(serverId).any { it.id == parentId }) {
                    database.getLibraryMediaIds(serverId, parentId)
                } else null
            val candidates = mutableListOf<FindroidItem>()
            if (allowed == null || BaseItemKind.MOVIE in allowed) {
                candidates += allMovies.filter { libraryIds == null || it.id in libraryIds }
                    .mapNotNull { runCatching { getMovie(it.id) }.getOrNull() }
            }
            if (allowed == null || BaseItemKind.SERIES in allowed) {
                candidates += allShows.filter { libraryIds == null || it.id in libraryIds }
                    .mapNotNull { runCatching { getShow(it.id) }.getOrNull() }
            }
            if (recursive && (allowed == null || BaseItemKind.EPISODE in allowed)) {
                candidates += allEpisodes.filter { libraryIds == null || it.id in libraryIds }
                    .mapNotNull { runCatching { getEpisode(it.id) }.getOrNull() }
            }
            if (allowed == null || BaseItemKind.SEASON in allowed) {
                candidates += allSeasons.filter { libraryIds == null || it.id in libraryIds }
                    .map { it.toFindroidSeason(database, jellyfinApi.userId!!) }
            }
            val sorted = if (sortOrder == SortOrder.DESCENDING) candidates.sortedByDescending { it.name.lowercase() }
                else candidates.sortedBy { it.name.lowercase() }
            sorted.drop(startIndex ?: 0).let { items -> if (limit != null) items.take(limit) else items }
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<FindroidItem>> {
        return flowOf(PagingData.from(getItems(parentId, includeTypes, recursive, sortBy, sortOrder)))
    }

    override suspend fun getPerson(personId: UUID): FindroidPerson =
        withContext(Dispatchers.IO) {
            val person = database.getCachedPersonDetails(currentServerId(), personId)
                ?: throw IllegalStateException("Person is not cached for offline use")
            val image = File(context.filesDir, "images/people/$personId/primary")
            FindroidPerson(
                id = personId,
                name = person.name,
                overview = person.overview,
                images = FindroidImages(primary = image.takeIf { it.exists() }?.let { Uri.fromFile(it) }),
            )
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            if (personIds.isEmpty()) return@withContext emptyList()
            database.getItemIdsForPeople(currentServerId(), personIds).distinct().mapNotNull { getItem(it) }
                .filter { includeTypes == null || includeTypes.any { kind ->
                    when (kind) {
                        BaseItemKind.MOVIE -> it is FindroidMovie
                        BaseItemKind.SERIES -> it is FindroidShow
                        BaseItemKind.EPISODE -> it is FindroidEpisode
                        else -> false
                    }
                } }
        }

    override suspend fun getFavoriteItems(): List<FindroidItem> =
        getItems(includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE), recursive = true)
            .filter { it.favorite }

    override suspend fun getSearchItems(query: String): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId()
            val movies = database.searchMovies(serverId, query).mapNotNull { runCatching { getMovie(it.id) }.getOrNull() }
            val shows = database.searchShows(serverId, query).mapNotNull { runCatching { getShow(it.id) }.getOrNull() }
            val episodes = database.searchEpisodes(serverId, query).mapNotNull { runCatching { getEpisode(it.id) }.getOrNull() }
            movies + shows + episodes
        }
    }

    override suspend fun getSuggestions(): List<FindroidItem> =
        getItems(includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)).take(6)

    override suspend fun getGenreNames(): List<String> =
        withContext(Dispatchers.IO) { database.getGenres(currentServerId()) }

    override suspend fun getGenreItems(genre: String): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            database.getMediaIdsForGenre(currentServerId(), genre).distinct().mapNotNull { id ->
                runCatching { getMovie(id) }.getOrNull() ?: runCatching { getShow(id) }.getOrNull()
            }
        }

    override suspend fun getSimilarItems(itemId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            database.getSimilarMediaIds(currentServerId(), itemId)
                .mapNotNull { id -> getItem(id) }
                .filter { it is FindroidMovie || it is FindroidShow }
        }

    override suspend fun getResumeItems(): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId()
            val movies = database.getMoviesByServerId(serverId)
                .filter { runCatching { database.getUserDataOrCreateNew(it.id, jellyfinApi.userId!!).playbackPositionTicks > 0 }.getOrDefault(false) }
                .mapNotNull { runCatching { getMovie(it.id) }.getOrNull() }
                .filter { it.playbackPositionTicks > 0 }
            val episodes = database.getEpisodesByServerId(serverId)
                .filter { runCatching { database.getUserDataOrCreateNew(it.id, jellyfinApi.userId!!).playbackPositionTicks > 0 }.getOrDefault(false) }
                .mapNotNull { runCatching { getEpisode(it.id) }.getOrNull() }
                .filter { it.playbackPositionTicks > 0 }
            movies + episodes
        }
    }

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        getItems(
            parentId = parentId,
            includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
            recursive = true,
            limit = 16,
        )

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            database.getSeasonsByShowId(seriesId).map {
                it.toFindroidSeason(database, jellyfinApi.userId!!)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<FindroidEpisode>()
            val shows =
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .filter { if (seriesId != null) it.id == seriesId else true }
            for (show in shows) {
                val episodes =
                    database.getEpisodesByShowId(show.id).map {
                        it.toFindroidEpisode(database, jellyfinApi.userId!!)
                    }
                val indexOfLastPlayed = episodes.indexOfLast { it.played }
                if (episodes.isEmpty()) continue
                if (indexOfLastPlayed == -1) {
                    result.add(episodes.first())
                } else {
                    episodes.getOrNull(indexOfLastPlayed + 1)?.let { result.add(it) }
                }
            }
            result.filter { it.playbackPositionTicks == 0L }
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
            val items =
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toFindroidEpisode(database, jellyfinApi.userId!!)
                }
            if (startItemId != null) return@withContext items.dropWhile { it.id != startItemId }
            items
        }

    private fun cachedImages(itemId: UUID, seriesId: UUID? = null): FindroidImages {
        fun image(ownerId: UUID, type: String): Uri? =
            File(context.filesDir, "images/$ownerId/$type")
                .takeIf { it.isFile }
                ?.let(Uri::fromFile)

        return FindroidImages(
            primary = image(itemId, "primary"),
            backdrop = image(itemId, "backdrop"),
            logo = image(itemId, "logo"),
            showPrimary = seriesId?.let { image(it, "primary") },
            showBackdrop = seriesId?.let { image(it, "backdrop") },
            showLogo = seriesId?.let { image(it, "logo") },
        )
    }

    private fun currentServerId(): String =
        appPreferences.getValue(appPreferences.currentServer)
            ?: throw IllegalStateException("No current Jellyfin server selected")

    private suspend fun getCachedPeople(itemId: UUID): List<FindroidItemPerson> =
        database.getMediaPeople(currentServerId(), itemId).mapNotNull { person ->
            val kind = runCatching { PersonKind.valueOf(person.type.uppercase()) }.getOrNull() ?: return@mapNotNull null
            val imageFile = File(context.filesDir, "images/people/${person.personId}/primary")
            val uri = imageFile.takeIf { it.exists() }?.let { Uri.fromFile(it) }
            FindroidItemPerson(
                id = person.personId,
                name = person.name,
                type = kind,
                role = person.role,
                image = FindroidItemPersonImage(uri, null),
            )
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            val itemName =
                runCatching { database.getMovie(itemId).name }.getOrNull()
                    ?: runCatching { database.getEpisode(itemId).name }.getOrNull()
                    ?: ""
            val resolution = if (itemName.isNotBlank()) localFolderMediaResolver.resolveWithStatus(itemId, itemName) else null
            buildList {
                resolution?.source?.let(::add)
                if (resolution != null && !resolution.scanCompleted) add(localFolderMediaResolver.unknownSource(itemId, itemName))
                addAll(database.getSources(itemId).map { it.toFindroidSource(database) })
            }
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String {
        TODO("Not yet implemented")
    }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) { database.getSegments(itemId).map { it.toFindroidSegment() } }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sources =
                    File(context.filesDir, "trickplay/$itemId").listFiles()
                        ?: return@withContext null
                File(sources.first(), index.toString()).readBytes()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun postCapabilities() {}

    override suspend fun postPlaybackStart(itemId: UUID) {}

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
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
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
        }
    }

    override fun getBaseUrl(): String {
        return ""
    }

    override suspend fun updateDeviceName(name: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getUserConfiguration(): UserConfiguration? {
        return null
    }

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
}
