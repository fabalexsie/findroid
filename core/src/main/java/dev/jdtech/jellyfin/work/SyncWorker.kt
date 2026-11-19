package dev.jdtech.jellyfin.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CachedJellyfinItemDto
import dev.jdtech.jellyfin.models.CachedLibraryDto
import dev.jdtech.jellyfin.models.CachedPersonDto
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.LibraryMediaDto
import dev.jdtech.jellyfin.models.MediaGenreDto
import dev.jdtech.jellyfin.models.MediaPersonDto
import dev.jdtech.jellyfin.models.SimilarMediaDto
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toFindroidChapters
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {
    private val httpClient by lazy { OkHttpClient() }
    private val metadataJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun doWork(): Result {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return Result.retry()
        }
        val jellyfinApi =
            JellyfinApi(
                androidContext = context.applicationContext,
                requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
            )

        return try {
            for (server in database.getServers()) {
                val serverWithAddressesAndUsers =
                    database.getServerWithAddressesAndUsers(server.id) ?: continue
                val serverAddress =
                    serverWithAddressesAndUsers.addresses.firstOrNull {
                        it.id == server.currentServerAddressId
                    } ?: continue

                // The library cache is keyed per server (not per account), so sync only the
                // currently selected account for this server.
                for (user in serverWithAddressesAndUsers.users.filter { it.id == server.currentUserId }) {
                    val accessToken = user.accessToken ?: continue
                    jellyfinApi.apply {
                        api.update(baseUrl = serverAddress.address, accessToken = accessToken)
                        userId = user.id
                    }
                    syncLibrary(jellyfinApi, server.id, user, accessToken, serverAddress.address)

                    val movies =
                        database.getMoviesByServerId(server.id).mapNotNull { movie ->
                            runCatching { movie.toFindroidMovie(database, user.id) }.getOrNull()
                        }
                    val episodes =
                        database.getEpisodesByServerId(server.id).mapNotNull { episode ->
                            runCatching { episode.toFindroidEpisode(database, user.id) }.getOrNull()
                        }
                    syncUserData(jellyfinApi, user, movies + episodes)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Library/user-data sync failed")
            Result.retry()
        }
    }

    /** Fetches every supported item in every Jellyfin view, in bounded pages. */
    private suspend fun syncLibrary(
        jellyfinApi: JellyfinApi,
        serverId: String,
        user: User,
        accessToken: String,
        baseUrl: String,
    ) {
        val views = jellyfinApi.userViewApi.getUserViews(user.id).content.items
        database.deleteCachedLibraries(serverId)
        database.insertCachedLibraries(
            views.map { view ->
                CachedLibraryDto(
                    serverId = serverId,
                    id = view.id,
                    name = view.name.orEmpty(),
                    collectionType = view.collectionType?.serialName,
                    primaryImageTag = view.imageTags?.get(ImageType.PRIMARY),
                )
            }
        )

        val itemKinds =
            listOf(
                BaseItemKind.MOVIE,
                BaseItemKind.SERIES,
                BaseItemKind.SEASON,
                BaseItemKind.EPISODE,
            )
        val fields =
            listOf(
                ItemFields.OVERVIEW,
                ItemFields.GENRES,
                ItemFields.PEOPLE,
                ItemFields.CHAPTERS,
                ItemFields.MEDIA_STREAMS,
                ItemFields.PROVIDER_IDS,
                ItemFields.STUDIOS,
                ItemFields.TAGLINES,
                ItemFields.DATE_CREATED,
                ItemFields.PARENT_ID,
                ItemFields.PATH,
                ItemFields.SORT_NAME,
                ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            )

        for (view in views) {
            downloadImage(
                imageUrl(baseUrl, view.id, ImageType.PRIMARY, view.imageTags?.get(ImageType.PRIMARY)),
                accessToken,
                File(context.filesDir, "images/${view.id}/primary"),
            )
            val libraryItems = mutableListOf<LibraryMediaDto>()
            var startIndex = 0
            while (true) {
                val page =
                    jellyfinApi.libraryApi
                        .getItems(
                            userId = user.id,
                            parentId = view.id,
                            includeItemTypes = itemKinds,
                            recursive = true,
                            startIndex = startIndex,
                            limit = PAGE_SIZE,
                            fields = fields,
                        )
                        .content
                val items = page.items
                if (items.isEmpty()) break

                libraryItems += items.filter { item -> item.type?.let(itemKinds::contains) == true }.map { item ->
                    LibraryMediaDto(serverId = serverId, libraryId = view.id, itemId = item.id)
                }
                for (item in items) {
                    try {
                        persistItem(serverId, user, item)
                        cachePersonDetails(jellyfinApi, serverId, user, item)
                        downloadItemImages(baseUrl, accessToken, item)
                        if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES) {
                            syncSimilarItems(jellyfinApi, serverId, user, item)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Unable to cache item ${item.id}")
                    }
                }

                startIndex += items.size
                if (items.size < PAGE_SIZE) break
            }
            // Replace the view membership only after all pages completed successfully. A
            // transient server failure must not make a previously cached library disappear.
            database.deleteLibraryMedia(serverId, view.id)
            if (libraryItems.isNotEmpty()) database.insertLibraryMedia(libraryItems)
        }
    }

    private suspend fun persistItem(serverId: String, user: User, item: BaseItemDto) {
        database.insertCachedJellyfinItem(
            CachedJellyfinItemDto(
                serverId = serverId,
                itemId = item.id,
                payload = metadataJson.encodeToString(item),
                syncedAt = System.currentTimeMillis(),
            )
        )
        when (item.type) {
            BaseItemKind.MOVIE -> {
                database.insertMovie(
                    FindroidMovieDto(
                        id = item.id,
                        serverId = serverId,
                        name = item.name.orEmpty(),
                        originalTitle = item.originalTitle,
                        overview = item.overview.orEmpty(),
                        runtimeTicks = item.runTimeTicks ?: 0L,
                        premiereDate = item.premiereDate,
                        communityRating = item.communityRating,
                        officialRating = item.officialRating,
                        status = item.status ?: "Ended",
                        productionYear = item.productionYear,
                        endDate = item.endDate,
                        chapters = item.toFindroidChapters(),
                    )
                )
            }
            BaseItemKind.SERIES -> {
                database.insertShow(
                    FindroidShowDto(
                        id = item.id,
                        serverId = serverId,
                        name = item.name.orEmpty(),
                        originalTitle = item.originalTitle,
                        overview = item.overview.orEmpty(),
                        runtimeTicks = item.runTimeTicks ?: 0L,
                        communityRating = item.communityRating,
                        officialRating = item.officialRating,
                        status = item.status ?: "Ended",
                        productionYear = item.productionYear,
                        endDate = item.endDate,
                    )
                )
            }
            BaseItemKind.SEASON -> {
                val seriesId = item.seriesId ?: return
                database.insertShowIfMissing(seriesId, serverId)
                database.insertSeason(
                    FindroidSeasonDto(
                        id = item.id,
                        seriesId = seriesId,
                        name = item.name.orEmpty(),
                        seriesName = item.seriesName.orEmpty(),
                        overview = item.overview.orEmpty(),
                        indexNumber = item.indexNumber ?: 0,
                    )
                )
            }
            BaseItemKind.EPISODE -> {
                val seriesId = item.seriesId ?: return
                val seasonId = item.seasonId ?: return
                database.insertShowIfMissing(seriesId, serverId)
                database.insertEpisodeSeasonIfMissing(item, seasonId, seriesId)
                database.insertEpisode(
                    FindroidEpisodeDto(
                        id = item.id,
                        serverId = serverId,
                        seasonId = seasonId,
                        seriesId = seriesId,
                        name = item.name.orEmpty(),
                        seriesName = item.seriesName.orEmpty(),
                        overview = item.overview.orEmpty(),
                        indexNumber = item.indexNumber ?: 0,
                        indexNumberEnd = item.indexNumberEnd,
                        parentIndexNumber = item.parentIndexNumber ?: 0,
                        runtimeTicks = item.runTimeTicks ?: 0L,
                        premiereDate = item.premiereDate,
                        communityRating = item.communityRating,
                        chapters = item.toFindroidChapters(),
                    )
                )
            }
            else -> return
        }

        database.deleteMediaGenres(serverId, item.id)
        item.genres.orEmpty().distinct().filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
            database.insertMediaGenres(it.map { genre -> MediaGenreDto(serverId, item.id, genre) })
        }
        database.deleteMediaPeople(serverId, item.id)
        item.people.orEmpty().map { person ->
            MediaPersonDto(
                serverId = serverId,
                itemId = item.id,
                personId = person.id,
                name = person.name.orEmpty(),
                type = person.type.serialName,
                role = person.role.orEmpty(),
                primaryImageTag = person.primaryImageTag,
            )
        }.takeIf { it.isNotEmpty() }?.let(database::insertMediaPeople)

        database.insertUserData(
            dev.jdtech.jellyfin.models.FindroidUserDataDto(
                userId = user.id,
                itemId = item.id,
                played = item.userData?.played == true,
                favorite = item.userData?.isFavorite == true,
                playbackPositionTicks = item.userData?.playbackPositionTicks ?: 0L,
            )
        )
    }

    private suspend fun cachePersonDetails(
        jellyfinApi: JellyfinApi,
        serverId: String,
        user: User,
        item: BaseItemDto,
    ) {
        for (credit in item.people.orEmpty()) {
            if (database.getCachedPersonDetails(serverId, credit.id) != null) continue
            try {
                val person = jellyfinApi.libraryApi.getItem(credit.id, user.id).content
                database.insertCachedPerson(
                    CachedPersonDto(
                        serverId = serverId,
                        id = credit.id,
                        name = person.name ?: credit.name.orEmpty(),
                        overview = person.overview.orEmpty(),
                        primaryImageTag = person.imageTags?.get(ImageType.PRIMARY) ?: credit.primaryImageTag,
                    )
                )
            } catch (e: Exception) {
                Timber.d(e, "Unable to cache person ${credit.id}")
            }
        }
    }

    private suspend fun syncSimilarItems(
        jellyfinApi: JellyfinApi,
        serverId: String,
        user: User,
        item: BaseItemDto,
    ) {
        val recommendations =
            jellyfinApi.libraryApi
                .getSimilarItems(
                    itemId = item.id,
                    userId = user.id,
                    limit = SIMILAR_LIMIT,
                    fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PEOPLE),
                )
                .content
                .items
        database.deleteSimilarMedia(serverId, item.id)
        recommendations.forEachIndexed { index, recommendation ->
            if (
                recommendation.id != item.id &&
                    (recommendation.type == BaseItemKind.MOVIE || recommendation.type == BaseItemKind.SERIES)
            ) {
                // Jellyfin can return recommendations from another view; cache their metadata too.
                persistItem(serverId, user, recommendation)
                database.insertSimilarMedia(
                    listOf(
                        SimilarMediaDto(
                            serverId = serverId,
                            itemId = item.id,
                            similarItemId = recommendation.id,
                            sortOrder = index,
                        )
                    )
                )
            }
        }
    }

    private suspend fun downloadItemImages(baseUrl: String, accessToken: String, item: BaseItemDto) {
        val itemRoot = File(context.filesDir, "images/${item.id}")
        val images =
            listOfNotNull(
                imageUrl(baseUrl, item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY)) to
                    File(itemRoot, "primary"),
                imageUrl(baseUrl, item.id, ImageType.BACKDROP, item.backdropImageTags?.firstOrNull(), 0) to
                    File(itemRoot, "backdrop"),
                imageUrl(baseUrl, item.id, ImageType.LOGO, item.imageTags?.get(ImageType.LOGO)) to
                    File(itemRoot, "logo"),
                imageUrl(baseUrl, item.id, ImageType.ART, item.imageTags?.get(ImageType.ART)) to
                    File(itemRoot, "art"),
                imageUrl(baseUrl, item.id, ImageType.THUMB, item.imageTags?.get(ImageType.THUMB)) to
                    File(itemRoot, "thumb"),
            )
        for ((uri, file) in images) downloadImage(uri, accessToken, file)

        item.chapters.orEmpty().forEachIndexed { index, chapter ->
            val uri = imageUrl(baseUrl, item.id, ImageType.CHAPTER, null, index)
            downloadImage(uri, accessToken, File(itemRoot, "chapters/$index"))
        }

        item.people.orEmpty().forEach { person ->
            val tag = person.primaryImageTag ?: return@forEach
            val uri = imageUrl(baseUrl, person.id, ImageType.PRIMARY, tag)
            downloadImage(uri, accessToken, File(context.filesDir, "images/people/${person.id}/primary"))
        }
    }

    private fun imageUrl(
        baseUrl: String,
        itemId: UUID,
        imageType: ImageType,
        tag: String?,
        index: Int? = null,
    ): String? {
        if (tag.isNullOrBlank() && imageType != ImageType.CHAPTER) return null
        val builder =
            Uri.parse(baseUrl).buildUpon()
                .appendPath("Items")
                .appendPath(itemId.toString())
                .appendPath("Images")
                .appendPath(imageType.serialName)
        index?.let { builder.appendPath(it.toString()) }
        tag?.let { builder.appendQueryParameter("tag", it) }
        return builder.build().toString()
    }

    private suspend fun downloadImage(url: String?, accessToken: String, file: File) {
        if (url == null) return
        val imageTag = Uri.parse(url).getQueryParameter("tag")
        val tagFile = File(file.parentFile, "${file.name}.tag")
        if (file.isFile && (imageTag == null || runCatching { tagFile.readText() == imageTag }.getOrDefault(false))) return
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("X-Emby-Token", accessToken).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    val bytes = response.body.bytes()
                    if (bytes.isNotEmpty()) {
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        if (imageTag != null) tagFile.writeText(imageTag)
                    }
                }
            } catch (e: IOException) {
                Timber.w(e, "Image download failed: $url")
            }
        }
    }

    private suspend fun syncUserData(
        jellyfinApi: JellyfinApi,
        user: User,
        items: List<dev.jdtech.jellyfin.models.FindroidItem>,
    ) {
        for (item in items) {
            val userData = database.getUserDataToBeSynced(user.id, item.id) ?: continue
            try {
                jellyfinApi.userDataApi.updateItemUserData(
                    itemId = item.id,
                    userId = user.id,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = userData.playbackPositionTicks,
                            isFavorite = userData.favorite,
                            played = userData.played,
                        ),
                )
                database.setUserDataToBeSynced(user.id, item.id, false)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val SIMILAR_LIMIT = 20
    }
}

private suspend fun ServerDatabaseDao.insertShowIfMissing(seriesId: UUID, serverId: String) {
    if (findShow(seriesId) == null) {
        insertShow(
            FindroidShowDto(
                id = seriesId,
                serverId = serverId,
                name = "",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0,
                communityRating = null,
                officialRating = null,
                status = "Ended",
                productionYear = null,
                endDate = null,
            )
        )
    }
}

private suspend fun ServerDatabaseDao.insertEpisodeSeasonIfMissing(
    item: BaseItemDto,
    seasonId: UUID,
    seriesId: UUID,
) {
    if (runCatching { getSeason(seasonId) }.isFailure) {
        insertSeason(
            FindroidSeasonDto(
                id = seasonId,
                seriesId = seriesId,
                name = item.seasonName.orEmpty(),
                seriesName = item.seriesName.orEmpty(),
                overview = "",
                indexNumber = item.parentIndexNumber ?: 0,
            )
        )
    }
}
