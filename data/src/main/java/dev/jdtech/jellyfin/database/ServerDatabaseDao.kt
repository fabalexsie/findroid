package dev.jdtech.jellyfin.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMediaStreamDto
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidSegmentDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.models.CachedJellyfinItemDto
import dev.jdtech.jellyfin.models.CachedLibraryDto
import dev.jdtech.jellyfin.models.CachedPersonDto
import dev.jdtech.jellyfin.models.LibraryMediaDto
import dev.jdtech.jellyfin.models.MediaGenreDto
import dev.jdtech.jellyfin.models.MediaPersonDto
import dev.jdtech.jellyfin.models.SimilarMediaDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.ServerWithAddressesAndUsers
import dev.jdtech.jellyfin.models.User
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertUser(user: User)

    @Update suspend fun updateServer(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") suspend fun getServer(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") suspend fun getUser(id: UUID): User?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddresses(id: String): ServerWithAddresses

    @Query("SELECT * FROM serverAddresses WHERE id = :id")
    suspend fun getAddress(id: UUID): ServerAddress

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    suspend fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers")
    suspend fun getServersWithAddresses(): List<ServerWithAddresses>

    @Query("SELECT * FROM servers") suspend fun getServers(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") suspend fun getServersCount(): Int

    @Query("SELECT COUNT(*) FROM servers") fun observeServersCount(): Flow<Int>

    @Query("SELECT * FROM servers WHERE id = :id") fun observeServer(id: String): Flow<Server?>

    @Query("SELECT currentUserId FROM servers WHERE id = :id")
    fun observeServerCurrentUserId(id: String): Flow<UUID?>

    @Query("DELETE FROM servers WHERE id = :id") suspend fun deleteServer(id: String)

    @Query("DELETE FROM users WHERE id = :id") suspend fun deleteUser(id: UUID)

    @Query("DELETE FROM serverAddresses WHERE id = :id") suspend fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    suspend fun updateServerCurrentUser(serverId: String, userId: UUID)

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentUser(serverId: String): User?

    @Query(
        "SELECT * FROM serverAddresses WHERE id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMovie(movie: FindroidMovieDto)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: FindroidSourceDto)

    @Query("SELECT * FROM movies WHERE id = :id") suspend fun getMovie(id: UUID): FindroidMovieDto

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    suspend fun getSources(itemId: UUID): List<FindroidSourceDto>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    suspend fun getSourceByDownloadId(downloadId: Long): FindroidSourceDto?

    @Query("UPDATE sources SET path = :path WHERE id = :id")
    suspend fun setSourcePath(id: String, path: String)

    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)

    @Query("DELETE FROM movies WHERE id = :id") suspend fun deleteMovie(id: UUID)

    @Query(
        "UPDATE userdata SET playbackPositionTicks = :playbackPositionTicks WHERE itemId = :itemId AND userid = :userId"
    )
    suspend fun setPlaybackPositionTicks(itemId: UUID, userId: UUID, playbackPositionTicks: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaStream(mediaStream: FindroidMediaStreamDto)

    @Query("SELECT * FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun getMediaStreamsBySourceId(sourceId: String): List<FindroidMediaStreamDto>

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    suspend fun getMediaStreamByDownloadId(downloadId: Long): FindroidMediaStreamDto?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    suspend fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    suspend fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") suspend fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun deleteMediaStreamsBySourceId(sourceId: String)

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    suspend fun setPlayed(userId: UUID, itemId: UUID, played: Boolean)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    suspend fun setFavorite(userId: UUID, itemId: UUID, favorite: Boolean)

    @Query("SELECT * FROM movies ORDER BY name ASC") suspend fun getMovies(): List<FindroidMovieDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getMoviesByServerId(serverId: String): List<FindroidMovieDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertShow(show: FindroidShowDto)

    @Query("SELECT * FROM shows WHERE id = :id") suspend fun getShow(id: UUID): FindroidShowDto

    @Query("SELECT * FROM shows ORDER BY name ASC") suspend fun getShows(): List<FindroidShowDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getShowsByServerId(serverId: String): List<FindroidShowDto>

    @Query("DELETE FROM shows WHERE id = :id") suspend fun deleteShow(id: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeason(show: FindroidSeasonDto)

    @Query("SELECT * FROM seasons WHERE id = :id")
    suspend fun getSeason(id: UUID): FindroidSeasonDto

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    suspend fun getSeasonsByShowId(seriesId: UUID): List<FindroidSeasonDto>

    @Query("DELETE FROM seasons WHERE id = :id") suspend fun deleteSeason(id: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: FindroidEpisodeDto)

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: UUID): FindroidEpisodeDto

    @Query(
        "SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getEpisodesByShowId(seriesId: UUID): List<FindroidEpisodeDto>

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY indexNumber ASC")
    suspend fun getEpisodesBySeasonId(seasonId: UUID): List<FindroidEpisodeDto>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getEpisodesByServerId(serverId: String): List<FindroidEpisodeDto>

    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: FindroidSegmentDto)

    @Query("SELECT * FROM segments WHERE itemId = :itemId")
    suspend fun getSegments(itemId: UUID): List<FindroidSegmentDto>

    @Query("SELECT * FROM seasons") suspend fun getSeasons(): List<FindroidSeasonDto>

    @Query("SELECT * FROM episodes") suspend fun getEpisodes(): List<FindroidEpisodeDto>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    suspend fun getUserData(itemId: UUID, userId: UUID): FindroidUserDataDto?

    @Transaction
    suspend fun getUserDataOrCreateNew(itemId: UUID, userId: UUID): FindroidUserDataDto {
        var userData = getUserData(itemId, userId)

        // Create user data when there is none
        if (userData == null) {
            userData =
                FindroidUserDataDto(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            insertUserData(userData)
        }

        return userData
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserData(userData: FindroidUserDataDto)

    @Query("DELETE FROM userdata WHERE itemId = :itemId") suspend fun deleteUserData(itemId: UUID)

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    suspend fun getUserDataToBeSynced(userId: UUID, itemId: UUID): FindroidUserDataDto?

    @Query(
        "UPDATE userdata SET toBeSynced = :toBeSynced WHERE itemId = :itemId AND userId = :userId"
    )
    suspend fun setUserDataToBeSynced(userId: UUID, itemId: UUID, toBeSynced: Boolean)

    @Query("SELECT * FROM movies WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchMovies(serverId: String, name: String): List<FindroidMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchShows(serverId: String, name: String): List<FindroidShowDto>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchEpisodes(serverId: String, name: String): List<FindroidEpisodeDto>



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedJellyfinItem(item: CachedJellyfinItemDto)

    @Query("SELECT * FROM cachedJellyfinItems WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun getCachedJellyfinItem(serverId: String, itemId: UUID): CachedJellyfinItemDto?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedLibraries(items: List<CachedLibraryDto>)

    @Query("DELETE FROM cachedLibraries WHERE serverId = :serverId")
    suspend fun deleteCachedLibraries(serverId: String)

    @Query("SELECT * FROM cachedLibraries WHERE serverId = :serverId ORDER BY name COLLATE NOCASE")
    suspend fun getCachedLibraries(serverId: String): List<CachedLibraryDto>

    @Query("SELECT * FROM mediaPeople WHERE serverId = :serverId AND personId = :personId LIMIT 1")
    suspend fun getCachedPerson(serverId: String, personId: UUID): MediaPersonDto?

    @Query("SELECT * FROM mediaPeople WHERE serverId = :serverId AND personId = :personId")
    suspend fun getPersonCredits(serverId: String, personId: UUID): List<MediaPersonDto>

    @Query("SELECT DISTINCT itemId FROM mediaPeople WHERE serverId = :serverId AND personId IN (:personIds)")
    suspend fun getItemIdsForPeople(serverId: String, personIds: List<UUID>): List<UUID>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLibraryMedia(items: List<LibraryMediaDto>)

    @Query("DELETE FROM libraryMedia WHERE serverId = :serverId AND libraryId = :libraryId")
    suspend fun deleteLibraryMedia(serverId: String, libraryId: UUID)

    @Query("SELECT itemId FROM libraryMedia WHERE serverId = :serverId AND libraryId = :libraryId")
    suspend fun getLibraryMediaIds(serverId: String, libraryId: UUID): List<UUID>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedPerson(person: CachedPersonDto)

    @Query("SELECT * FROM cachedPeople WHERE serverId = :serverId AND id = :personId")
    suspend fun getCachedPersonDetails(serverId: String, personId: UUID): CachedPersonDto?

    @Query("SELECT genre FROM mediaGenres WHERE serverId = :serverId AND itemId = :itemId ORDER BY genre COLLATE NOCASE")
    suspend fun getGenresForItem(serverId: String, itemId: UUID): List<String>

    @Query("SELECT * FROM seasons WHERE id IN (:ids)")
    suspend fun getSeasonsByIds(ids: List<UUID>): List<FindroidSeasonDto>

    @Query("SELECT * FROM seasons")
    suspend fun getAllSeasons(): List<FindroidSeasonDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaGenres(genres: List<MediaGenreDto>)

    @Query("DELETE FROM mediaGenres WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteMediaGenres(serverId: String, itemId: UUID)

    @Query("SELECT DISTINCT genre FROM mediaGenres WHERE serverId = :serverId ORDER BY genre COLLATE NOCASE")
    suspend fun getGenres(serverId: String): List<String>

    @Query("SELECT itemId FROM mediaGenres WHERE serverId = :serverId AND genre = :genre")
    suspend fun getMediaIdsForGenre(serverId: String, genre: String): List<UUID>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSimilarMedia(items: List<SimilarMediaDto>)

    @Query("DELETE FROM similarMedia WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteSimilarMedia(serverId: String, itemId: UUID)

    @Query("SELECT similarItemId FROM similarMedia WHERE serverId = :serverId AND itemId = :itemId ORDER BY sortOrder ASC")
    suspend fun getSimilarMediaIds(serverId: String, itemId: UUID): List<UUID>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaPeople(items: List<MediaPersonDto>)

    @Query("DELETE FROM mediaPeople WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteMediaPeople(serverId: String, itemId: UUID)

    @Query("SELECT * FROM mediaPeople WHERE serverId = :serverId AND itemId = :itemId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getMediaPeople(serverId: String, itemId: UUID): List<MediaPersonDto>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun findMovie(id: UUID): FindroidMovieDto?

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun findShow(id: UUID): FindroidShowDto?

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name COLLATE NOCASE")
    suspend fun getAllMovies(serverId: String): List<FindroidMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name COLLATE NOCASE")
    suspend fun getAllShows(serverId: String): List<FindroidShowDto>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId ORDER BY seriesName COLLATE NOCASE, parentIndexNumber, indexNumber")
    suspend fun getAllEpisodes(serverId: String): List<FindroidEpisodeDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun getMoviesByIds(serverId: String, ids: List<UUID>): List<FindroidMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun getShowsByIds(serverId: String, ids: List<UUID>): List<FindroidShowDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrickplayInfo(trickplayInfoDto: FindroidTrickplayInfoDto)

    @Query("SELECT * FROM trickplayInfos WHERE sourceId = :sourceId")
    suspend fun getTrickplayInfo(sourceId: String): FindroidTrickplayInfoDto?
}
