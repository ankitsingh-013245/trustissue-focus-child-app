package com.trustissue.child

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri

/**
 * Resolves narrowly scoped study-tool handlers from Android intent contracts.
 *
 * No screen text or document content is inspected. A package is considered a
 * tool only when Android reports that it handles the relevant MIME/intent.
 */
class StudyToolResolver(
    private val context: Context
) {
    enum class ToolKind(
        val wireName: String,
        val title: String,
        val sessionEligible: Boolean
    ) {
        PDF_READER("pdf_reader", "PDF reader", true),
        GALLERY("gallery", "Gallery", false)
    }

    private val browserPackages by lazy {
        queryPackages(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        )
    }

    private val pdfPackages by lazy {
        queryPackages(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("content://com.trustissue.placeholder/document.pdf")
            ).apply {
                setDataAndType(
                    Uri.parse("content://com.trustissue.placeholder/document.pdf"),
                    "application/pdf"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ) - browserPackages
    }

    private val galleryPackages by lazy {
        (
            queryPackages(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            ) +
                queryPackages(
                    Intent(Intent.ACTION_PICK).apply { type = "image/*" }
                )
            ) - browserPackages
    }

    private val trustedSystemHelperPackages by lazy {
        val fixed = setOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.credentialmanager",
            "com.google.android.credentialmanager"
        )
        val resolved = (
            queryPackages(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            ) +
                queryPackages(
                    Intent("android.provider.action.PICK_IMAGES").apply {
                        type = "image/*"
                    }
                )
            ).filter(::isSystemPackage)
        fixed.filter(::isSystemPackage).toSet() + resolved
    }

    fun classify(packageName: String): ToolKind? {
        if (packageName.isBlank() || packageName == context.packageName) return null
        return when {
            pdfPackages.contains(packageName) -> ToolKind.PDF_READER
            galleryPackages.contains(packageName) -> ToolKind.GALLERY
            else -> null
        }
    }

    fun defaultPdfReaderPackages(): Set<String> = pdfPackages

    fun isEligibleDefaultPdfReader(packageName: String): Boolean {
        return packageName.isNotBlank() &&
            pdfPackages.contains(packageName) &&
            !browserPackages.contains(packageName)
    }

    fun isSelectedDefaultPdfReader(packageName: String): Boolean {
        return packageName == TrackerConfig.defaultPdfReaderPackage(context) &&
            isEligibleDefaultPdfReader(packageName)
    }

    fun isTrustedSystemHelper(packageName: String): Boolean {
        return trustedSystemHelperPackages.contains(packageName)
    }

    fun isBrowserPackage(packageName: String): Boolean {
        return browserPackages.contains(packageName)
    }

    private fun queryPackages(intent: Intent): Set<String> {
        @Suppress("DEPRECATION")
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun isSystemPackage(packageName: String): Boolean {
        @Suppress("DEPRECATION")
        val info = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return false
        return info.flags and (
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ) != 0
    }
}
