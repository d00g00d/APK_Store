package ru.d00g00d.apkstore.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StoreCatalog(
    val apps: List<AppModel>
)

@Serializable
data class AppModel(
    val id: String,
    val name: String,
    val version: String,
    val packageName: String,
    val iconUrl: String,
    val apkUrl: String,
    val releaseNotes: String = ""
)
