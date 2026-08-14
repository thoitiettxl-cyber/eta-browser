package com.thoitiettxl.eta.core

internal data class BrowserSessionSnapshot(
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val isHumanHandoffPending: Boolean = false,
    val lastClientId: String? = null,
    val lastRequestId: String? = null,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)
