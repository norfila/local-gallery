package com.luma.gallery.data

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/** A validation failure whose message is shown to the user, in the user's language. */
class UserMessageException(@StringRes val messageRes: Int) : IllegalArgumentException()

fun Throwable.userMessage(context: Context, @StringRes fallback: Int): String = userMessage(context.resources, fallback)

fun Throwable.userMessage(resources: Resources, @StringRes fallback: Int): String =
    resources.getString(if (this is UserMessageException) messageRes else fallback)
