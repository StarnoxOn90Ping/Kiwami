package nox.kiwami.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Studio(
    @SerialName("id") var id: Int,

    @SerialName("name") var name: String?,

    @SerialName("isAnimationStudio") var isAnimationStudio: Boolean?,

    @SerialName("media") var media: MediaConnection?,

    @SerialName("siteUrl") var siteUrl: String?,

    @SerialName("isFavourite") var isFavourite: Boolean?,

    @SerialName("favourites") var favourites: Int?,
)

@Serializable
data class StudioEdge(
    @SerialName("node") var node: Studio? = null,
    @SerialName("id") var id: Int? = null,
    @SerialName("isMain") var isMain: Boolean? = false
)

@Serializable
data class StudioConnection(
    @SerialName("edges") var edges: List<StudioEdge>? = null,

    @SerialName("nodes") var nodes: List<Studio>? = null,
)
