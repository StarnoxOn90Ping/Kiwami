package nox.kiwami.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class User(
    @SerialName("id") var id: Int = 0,

    @SerialName("name") var name: String? = null,

    @SerialName("about") var about: String? = null,

    @SerialName("avatar") var avatar: UserAvatar? = null,

    @SerialName("bannerImage") var bannerImage: String? = null,

    @SerialName("isFollowing") var isFollowing: Boolean? = null,

    @SerialName("isFollower") var isFollower: Boolean? = null,

    @SerialName("isBlocked") var isBlocked: Boolean? = null,

    @SerialName("options") var options: UserOptions? = null,

    @SerialName("mediaListOptions") var mediaListOptions: MediaListOptions? = null,

    @SerialName("favourites") var favourites: Favourites? = null,

    @SerialName("statistics") var statistics: UserStatisticTypes? = null,

    @SerialName("unreadNotificationCount") var unreadNotificationCount: Int? = null,

    @SerialName("siteUrl") var siteUrl: String? = null,

) : java.io.Serializable

@Serializable
data class UserOptions(
    @SerialName("titleLanguage") var titleLanguage: UserTitleLanguage? = null,

    @SerialName("displayAdultContent") var displayAdultContent: Boolean? = null,

    @SerialName("airingNotifications") var airingNotifications: Boolean? = null,
    @SerialName("profileColor") var profileColor: String? = null,
    @SerialName("timezone") var timezone: String? = null,
    @SerialName("activityMergeTime") var activityMergeTime: Int? = null,
    @SerialName("staffNameLanguage") var staffNameLanguage: UserStaffNameLanguage? = null,
    @SerialName("restrictMessagesToFollowing") var restrictMessagesToFollowing: Boolean? = null,

) : java.io.Serializable

@Serializable
data class UserAvatar(
    @SerialName("large") var large: String? = null,

    @SerialName("medium") var medium: String? = null,
) : java.io.Serializable

@Serializable
data class UserStatisticTypes(
    @SerialName("anime") var anime: UserStatistics? = null,
    @SerialName("manga") var manga: UserStatistics? = null
) : java.io.Serializable

@Serializable
enum class UserTitleLanguage {
    @SerialName("ENGLISH")
    ENGLISH,

    @SerialName("ROMAJI")
    ROMAJI,

    @SerialName("NATIVE")
    NATIVE
}

@Serializable
enum class UserStaffNameLanguage {
    @SerialName("ROMAJI_WESTERN")
    ROMAJI_WESTERN,

    @SerialName("ROMAJI")
    ROMAJI,

    @SerialName("NATIVE")
    NATIVE
}

@Serializable
enum class ScoreFormat {
    @SerialName("POINT_100")
    POINT_100,

    @SerialName("POINT_10_DECIMAL")
    POINT_10_DECIMAL,

    @SerialName("POINT_10")
    POINT_10,

    @SerialName("POINT_5")
    POINT_5,

    @SerialName("POINT_3")
    POINT_3,
}

@Serializable
data class UserStatistics(
    @SerialName("count") var count: Int?,
    @SerialName("meanScore") var meanScore: Float?,
    @SerialName("standardDeviation") var standardDeviation: Float?,
    @SerialName("minutesWatched") var minutesWatched: Int?,
    @SerialName("episodesWatched") var episodesWatched: Int?,
    @SerialName("chaptersRead") var chaptersRead: Int?,
    @SerialName("volumesRead") var volumesRead: Int?,
)

@Serializable
data class Favourites(
    @SerialName("anime") var anime: MediaConnection?,

    @SerialName("manga") var manga: MediaConnection?,

    @SerialName("characters") var characters: CharacterConnection?,

    @SerialName("staff") var staff: StaffConnection?,

    @SerialName("studios") var studios: StudioConnection?,
)

@Serializable
data class MediaListOptions(
    @SerialName("scoreFormat") var scoreFormat: ScoreFormat?,

    @SerialName("rowOrder") var rowOrder: String?,

    @SerialName("animeList") var animeList: MediaListTypeOptions?,

    @SerialName("mangaList") var mangaList: MediaListTypeOptions?,
)

@Serializable
data class MediaListTypeOptions(
    @SerialName("sectionOrder") var sectionOrder: List<String>?,

    @SerialName("customLists") var customLists: List<String>?,
)
